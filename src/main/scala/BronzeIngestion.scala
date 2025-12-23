package citypulse

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger

object BronzeIngestion {
  def main(args: Array[String]): Unit = {
    
    // 1. Initialisation de la Session Spark
    val spark = SparkSession.builder()
      .appName("CityPulse Bronze Ingestion")
      .master("local[*]") // Pour tester en local, sera surchargé par spark-submit
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")
    import spark.implicits._

    // --- FONCTION GÉNÉRIQUE D'INGESTION ---
    def ingestTopic(topic: String, path: String, checkpoint: String): Unit = {
      
      // 2. Lecture Stream depuis Kafka
      val dfRaw = spark.readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", "citypulse-kafka:9092") // Nom du service Docker
        .option("subscribe", topic)
        .option("startingOffsets", "earliest")
        .load()

      // 3. Transformation simple (Cast en String et ajout timestamp ingestion)
      val dfBronze = dfRaw.selectExpr("CAST(value AS STRING) as json_payload")
        .withColumn("ingestion_time", current_timestamp())
        .withColumn("ingestion_date", date_format(col("ingestion_time"), "yyyy-MM-dd"))

      // 4. Écriture dans HDFS (Format Texte/JSON)
      val query = dfBronze.writeStream
        .format("json") // On garde le format JSON brut ligne par ligne
        .option("path", path)
        .option("checkpointLocation", checkpoint)
        .partitionBy("ingestion_date") // Partitionnement pour la performance
        .trigger(Trigger.ProcessingTime("10 seconds")) // Micro-batch toutes les 10s
        .start()
        
      // Ne pas attendre ici, sinon on bloque le deuxième stream
    }

    // Lancement des deux streams
    ingestTopic(
      "traffic-raw", 
      "hdfs://citypulse-namenode:9000/data/bronze/traffic",
      "/tmp/checkpoints/bronze_traffic"
    )
    
    ingestTopic(
      "pollution-raw", 
      "hdfs://citypulse-namenode:9000/data/bronze/pollution",
      "/tmp/checkpoints/bronze_pollution"
    )

    // Attendre la fin de l'exécution (infini)
    spark.streams.awaitAnyTermination()
  }
}