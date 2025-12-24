package citypulse
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.streaming.Trigger

object SilverProcessing {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("CityPulse Silver").getOrCreate()
    import spark.implicits._
    spark.sparkContext.setLogLevel("WARN")

    val trafficSchema = new StructType().add("sensor_id", StringType).add("district", StringType).add("lat", DoubleType).add("lon", DoubleType).add("speed_kmh", IntegerType).add("vehicle_count", IntegerType).add("timestamp", StringType)
    val pollutionSchema = new StructType().add("sensor_id", StringType).add("district", StringType).add("pm25", IntegerType).add("co2", IntegerType).add("timestamp", StringType)

    def process(topic: String, schema: StructType, path: String) = {
      spark.readStream.format("kafka").option("kafka.bootstrap.servers", "citypulse-kafka:9092").option("subscribe", topic).load()
        .selectExpr("CAST(value AS STRING) as json").select(from_json($"json", schema).as("data")).select("data.*")
        .withColumn("event_time", to_timestamp($"timestamp"))
        .drop("timestamp")
        .writeStream.format("parquet").option("path", path).option("checkpointLocation", s"/tmp/ckpt_$topic").partitionBy("district").trigger(Trigger.ProcessingTime("10 seconds")).start()
    }
    process("traffic-raw", trafficSchema, "hdfs://citypulse-namenode:9000/data/silver/traffic")
    process("pollution-raw", pollutionSchema, "hdfs://citypulse-namenode:9000/data/silver/pollution")
    spark.streams.awaitAnyTermination()
  }
}