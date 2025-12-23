name := "CityPulse"
version := "1.0"
scalaVersion := "2.12.18" // Compatible avec Spark 3.3.0

val sparkVersion = "3.3.0"

libraryDependencies ++= Seq(
  // Core Spark
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.spark" %% "spark-streaming" % sparkVersion,
  
  // Connecteur Kafka (Structuré Streaming)
  "org.apache.spark" %% "spark-sql-kafka-0-10" % sparkVersion,
  
  // Connecteur Cassandra
  "com.datastax.spark" %% "spark-cassandra-connector" % "3.3.0",
  
  // Logging
  "ch.qos.logback" % "logback-classic" % "1.2.10"
)

// Évite les erreurs de déduplication lors de l'assemblage du JAR final
assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}