package citypulse
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.types._
import org.apache.spark.ml.PipelineModel

object GoldAggregations {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("CityPulse Gold").config("spark.cassandra.connection.host", "citypulse-cassandra").getOrCreate()
    import spark.implicits._
    spark.sparkContext.setLogLevel("WARN")

    val tSchema = new StructType().add("sensor_id", StringType).add("district", StringType).add("lat", DoubleType).add("lon", DoubleType).add("speed_kmh", IntegerType).add("vehicle_count", IntegerType).add("timestamp", StringType)
    val pSchema = new StructType().add("sensor_id", StringType).add("district", StringType).add("pm25", IntegerType).add("co2", IntegerType).add("timestamp", StringType)

    def read(topic: String) = spark.readStream.format("kafka").option("kafka.bootstrap.servers", "citypulse-kafka:9092").option("subscribe", topic).load().selectExpr("CAST(value AS STRING) as json")

    val tRaw = read("traffic-raw").select(from_json($"json", tSchema).as("d")).select("d.*").select($"district", to_timestamp($"timestamp").as("time"), $"speed_kmh", $"vehicle_count".as("density"), lit(null).as("pm25"))
    val pRaw = read("pollution-raw").select(from_json($"json", pSchema).as("d")).select("d.*").select($"district", to_timestamp($"timestamp").as("time"), lit(null).as("speed_kmh"), lit(null).as("density"), $"pm25")

    val kpis = tRaw.unionByName(pRaw).withWatermark("time", "1 minute").groupBy(window($"time", "1 minute"), $"district").agg(avg("speed_kmh").as("avg_speed"), max("density").as("max_density"), avg("pm25").as("avg_pm25"))
      .withColumn("window_end", $"window.end")
      .withColumn("alert_level", when($"avg_pm25" > 80, "CRITIQUE").when($"avg_pm25" > 50, "WARNING").otherwise("NORMAL"))
      .withColumnRenamed("avg_speed", "speed_kmh").withColumnRenamed("max_density", "vehicle_count")

    val modelPath = "hdfs://citypulse-namenode:9000/models/pollution_v1"

    kpis.writeStream.foreachBatch { (batch: org.apache.spark.sql.DataFrame, id: Long) =>
      if (!batch.isEmpty) {
        try {
          val model = PipelineModel.load(modelPath)
          val pred = model.transform(batch).withColumnRenamed("prediction", "predicted_pm25")
            .withColumnRenamed("speed_kmh", "avg_speed").withColumnRenamed("vehicle_count", "max_density")
            .select("district", "window_end", "avg_speed", "max_density", "avg_pm25", "predicted_pm25", "alert_level")
          
          println(s"Writing Batch $id with ML Prediction")
          pred.write.format("org.apache.spark.sql.cassandra").options(Map("keyspace" -> "citypulse", "table" -> "district_stats")).mode("append").save()
        } catch {
          case _: Exception => println("ML Model not ready yet.")
        }
      }
    }.trigger(Trigger.ProcessingTime("30 seconds")).start().awaitTermination()
  }
}

