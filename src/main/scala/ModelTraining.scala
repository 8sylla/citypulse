package citypulse
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.ml.regression.LinearRegression
import org.apache.spark.ml.Pipeline

object ModelTraining {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("CityPulse ML Training").getOrCreate()
    import spark.implicits._
    spark.sparkContext.setLogLevel("WARN")

    val tDF = spark.read.parquet("hdfs://citypulse-namenode:9000/data/silver/traffic").withColumnRenamed("district", "t_d").withColumn("time", to_timestamp($"event_time"))
    val pDF = spark.read.parquet("hdfs://citypulse-namenode:9000/data/silver/pollution").withColumnRenamed("district", "p_d").withColumn("time", to_timestamp($"event_time"))

    val data = tDF.join(pDF, $"t_d" === $"p_d" && date_trunc("hour", tDF("time")) === date_trunc("hour", pDF("time")))
      .select($"speed_kmh".cast("double"), $"vehicle_count".cast("double"), $"pm25".cast("double").as("label")).na.drop()

    if (data.count() < 10) { println("PAS ASSEZ DE DONNÉES POUR ENTRAINER !"); return }

    val assembler = new VectorAssembler().setInputCols(Array("speed_kmh", "vehicle_count")).setOutputCol("features")
    val lr = new LinearRegression().setMaxIter(10).setRegParam(0.3)
    val model = new Pipeline().setStages(Array(assembler, lr)).fit(data)

    model.write.overwrite().save("hdfs://citypulse-namenode:9000/models/pollution_v1")
    println(">>> MODÈLE ENTRAINÉ ET SAUVEGARDÉ ! <<<")
  }
}
