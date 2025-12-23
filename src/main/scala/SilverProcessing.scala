package citypulse

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.streaming.Trigger

object SilverProcessing {
  def main(args: Array[String]): Unit = {
    
    // 1. Initialisation Spark
    val spark = SparkSession.builder()
      .appName("CityPulse Silver Processing")
      .getOrCreate()

    import spark.implicits._
    spark.sparkContext.setLogLevel("WARN")

    // 2. Schémas Stricts (Typage Fort)
    val trafficSchema = new StructType()
      .add("sensor_id", StringType)
      .add("district", StringType)
      .add("lat", DoubleType)
      .add("lon", DoubleType)
      .add("speed_kmh", IntegerType)
      .add("vehicle_count", IntegerType)
      .add("timestamp", StringType) // On le reçoit en String ISO8601

    val pollutionSchema = new StructType()
      .add("sensor_id", StringType)
      .add("district", StringType)
      .add("pm25", IntegerType)
      .add("co2", IntegerType)
      .add("timestamp", StringType)

    // 3. Fonction générique de traitement (Clean & Enrich)
    def processLayer(
        topic: String, 
        schema: StructType, 
        hdfsPath: String, 
        ckptPath: String, 
        filterLogic: org.apache.spark.sql.DataFrame => org.apache.spark.sql.DataFrame
    ): Unit = {
      
      // A. Lecture Kafka (Earliest pour reprendre l'historique)
      val rawStream = spark.readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", "citypulse-kafka:9092")
        .option("subscribe", topic)
        .option("startingOffsets", "earliest")
        .load()

      // B. Parsing & Structuration
      val structuredStream = rawStream
        .selectExpr("CAST(value AS STRING) as json_str")
        .select(from_json($"json_str", schema).as("data"))
        .select("data.*")
        // Conversion String -> Timestamp natif Spark
        .withColumn("event_time", to_timestamp($"timestamp"))
        // Gestion des retards (1 min max)
        .withWatermark("event_time", "1 minute") 
        .drop("timestamp")

      // C. Application des Règles Métier (Quality Gates)
      val cleanStream = filterLogic(structuredStream)

      // D. Écriture Parquet Partitionnée
      cleanStream.writeStream
        .format("parquet")
        .option("path", hdfsPath)
        .option("checkpointLocation", ckptPath)
        .partitionBy("district") // Partitionnement physique
        .trigger(Trigger.ProcessingTime("30 seconds")) // Batch toutes les 30s
        .start()
    }

    // --- PIPELINE TRAFFIC ---
    // Règle : Vitesse > 0 et < 200, Lat/Lon non null
    processLayer(
      "traffic-raw",
      trafficSchema,
      "hdfs://citypulse-namenode:9000/data/silver/traffic",
      "/tmp/checkpoints/silver_traffic",
      df => df.filter($"speed_kmh" >= 0 && $"speed_kmh" <= 200 && $"lat".isNotNull)
    )

    // --- PIPELINE POLLUTION ---
    // Règle : Valeurs positives
    processLayer(
      "pollution-raw",
      pollutionSchema,
      "hdfs://citypulse-namenode:9000/data/silver/pollution",
      "/tmp/checkpoints/silver_pollution",
      df => df.filter($"pm25" >= 0 && $"co2" >= 0)
    )

    // Attente infinie
    spark.streams.awaitAnyTermination()
  }
}