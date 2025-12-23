package citypulse

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.types._

object GoldAggregations {
  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder()
      .appName("CityPulse Gold")
      .config("spark.cassandra.connection.host", "citypulse-cassandra")
      .getOrCreate()

    import spark.implicits._
    spark.sparkContext.setLogLevel("WARN")

    // Schémas
    val trafficSchema = new StructType()
      .add("sensor_id", StringType).add("district", StringType)
      .add("lat", DoubleType).add("lon", DoubleType)
      .add("speed_kmh", IntegerType).add("vehicle_count", IntegerType)
      .add("timestamp", StringType)
      
    val pollutionSchema = new StructType()
      .add("sensor_id", StringType).add("district", StringType)
      .add("pm25", IntegerType).add("co2", IntegerType)
      .add("timestamp", StringType)

    // Helper Lecture
    def readStream(topic: String) = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "citypulse-kafka:9092")
      .option("subscribe", topic)
      .option("startingOffsets", "earliest") 
      .load()
      .selectExpr("CAST(value AS STRING) as json_str")

    // 1. Préparer le Flux TRAFIC (avec des colonnes NULL pour la pollution)
    val trafficRaw = readStream("traffic-raw")
      .select(from_json($"json_str", trafficSchema).as("data")).select("data.*")
      .select(
        $"district",
        to_timestamp($"timestamp").as("event_time"),
        $"speed_kmh",
        $"vehicle_count".as("density"),
        lit(null).cast(IntegerType).as("pm25") // Colonne vide pour l'union
      )

    // 2. Préparer le Flux POLLUTION (avec des colonnes NULL pour le trafic)
    val pollutionRaw = readStream("pollution-raw")
      .select(from_json($"json_str", pollutionSchema).as("data")).select("data.*")
      .select(
        $"district",
        to_timestamp($"timestamp").as("event_time"),
        lit(null).cast(IntegerType).as("speed_kmh"), // Colonne vide pour l'union
        lit(null).cast(IntegerType).as("density"),   // Colonne vide pour l'union
        $"pm25"
      )

    // 3. UNION : On mélange tout dans un seul tuyau
    val unifiedStream = trafficRaw.unionByName(pollutionRaw)

    // 4. AGRÉGATION UNIQUE (Le secret pour éviter l'erreur)
    // La fonction avg() ignore automatiquement les valeurs nulles
    val aggregatedKPIs = unifiedStream
      .withWatermark("event_time", "1 minute")
      .groupBy(window($"event_time", "1 minute"), $"district")
      .agg(
        avg("speed_kmh").as("avg_speed"),
        max("density").as("max_density"),
        avg("pm25").as("avg_pm25")
      )

    // 5. Calcul des Alertes
    val finalStream = aggregatedKPIs
      .withColumn("window_end", $"window.end")
      .withColumn("alert_level",
        when($"avg_pm25" > 80 && $"max_density" > 40, "CRITIQUE")
        .when($"avg_pm25" > 50, "WARNING")
        .otherwise("NORMAL")
      )
      .select("district", "window_end", "avg_speed", "max_density", "avg_pm25", "alert_level")

    // 6. Écriture Cassandra
    finalStream.writeStream
      .outputMode("append")
      .foreachBatch { (batchDF: org.apache.spark.sql.DataFrame, batchId: Long) =>
        if (!batchDF.isEmpty) {
          println(s"--- Writing Batch $batchId to Cassandra ---")
          batchDF.show(5) // On affiche dans la console pour debug
          
          batchDF.write
            .format("org.apache.spark.sql.cassandra")
            .options(Map("keyspace" -> "citypulse", "table" -> "district_stats"))
            .mode("append")
            .save()
        }
      }
      .trigger(Trigger.ProcessingTime("30 seconds"))
      .start()
      .awaitTermination()
  }
}