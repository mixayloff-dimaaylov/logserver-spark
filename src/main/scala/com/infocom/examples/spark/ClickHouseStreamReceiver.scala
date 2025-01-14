/*
 * Copyright 2023 mixayloff-dimaaylov at github dot com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.infocom.examples.spark

import java.util.{Properties, UUID}
import com.infocom.examples.spark.data._
import com.infocom.examples.spark.schema.ClickHouse._
import com.infocom.examples.spark.serialization._
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.{ DStream, InputDStream }
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010._
import scala.reflect._

@deprecated("Broken until NovAtelLogReader updates to schema with sorted fields",
            "logserver-spark 0.2.0")
object StreamReceiver {
  def main(args: Array[String]): Unit = {
    System.out.println("Run main")

    if (args.length < 5) {
      System.out.println("Wrong arguments")
      printHelp()
      System.exit(1)
    }

    if (args.length > 5) {
      System.out.println("Extra arguments")
      printHelp()
      System.exit(1)
    }

    val recLat = args(0).toDouble
    val recLon = args(1).toDouble
    val recAlt = args(2).toDouble

    val sf = new StreamFunctions(recLat, recLon, recAlt)

    val kafkaServerAddress = args(3)
    val clickHouseServerAddress = args(4)
    val jdbcUri = s"jdbc:clickhouse://$clickHouseServerAddress"
    val clientUID = s"${UUID.randomUUID}"

    @transient val jdbcProps = new Properties()
    jdbcProps.setProperty("isolationLevel", "NONE")

    @transient val conf = new SparkConf().setAppName("GNSS Stream Receiver")
    val master = conf.getOption("spark.master")

    if (master.isEmpty) {
      conf.setMaster("local[*]")
    }

    System.out.println("Init conf")

    @transient val spark = SparkSession.builder.config(conf).getOrCreate()
    import spark.implicits._

    System.out.println("Create StreamingContext")
    val ssc = new StreamingContext(spark.sparkContext, Seconds(60))
    System.out.println("Created StreamingContext")

    def createKafkaStream[TDataPoint: ClassTag](topic: String): DStream[TDataPoint] = {
      val params = Map[String, Object](
        "bootstrap.servers" -> kafkaServerAddress,
        "key.deserializer" -> classOf[NullDeserializer],
        "value.deserializer" -> classOf[AvroDataPointDeserializer[Array[TDataPoint]]],
        "value.deserializer.type" -> classTag[Array[TDataPoint]].runtimeClass,
        "enable.auto.commit" -> (false: java.lang.Boolean),
        //"session.timeout.ms" -> "60000",
        "auto.offset.reset" -> "latest",
        "group.id" -> s"gnss-stream-receiver-${clientUID}-${topic}"
      )

      val stream = KafkaUtils.createDirectStream[Null, Array[TDataPoint]](
        ssc,
        PreferConsistent,
        Subscribe[Null, Array[TDataPoint]](Seq(topic), params)
      ).asInstanceOf[InputDStream[ConsumerRecord[Null, Array[TDataPoint]]]]

      stream.start()
      System.out.println($"Create $topic reader")
      stream.flatMap[TDataPoint](_.value())
    }

    // RANGE

    createKafkaStream[DataPointRange]("datapoint-raw-range") map toRow foreachRDD {
      _.toDF.write.mode("append").jdbc(jdbcUri, "rawdata.range", jdbcProps)
    }

    // ISMREDOBS

    createKafkaStream[DataPointIsmredobs]("datapoint-raw-ismredobs") map toRow foreachRDD {
      _.toDF.write.mode("append").jdbc(jdbcUri, "rawdata.ismredobs", jdbcProps)
    }

    // ISMDETOBS

    createKafkaStream[DataPointIsmdetobs]("datapoint-raw-ismdetobs") map toRow foreachRDD {
      _.toDF.write.mode("append").jdbc(jdbcUri, "rawdata.ismdetobs", jdbcProps)
    }

    // ISMRAWTEC

    createKafkaStream[DataPointIsmrawtec]("datapoint-raw-ismrawtec") map toRow foreachRDD {
      _.toDF.write.mode("append").jdbc(jdbcUri, "rawdata.ismrawtec", jdbcProps)
    }

    // SATXYZ2

    val toRowSatxyz2: DataPointSatxyz2 => Satxyz2Row = toRow(sf)
    createKafkaStream[DataPointSatxyz2]("datapoint-raw-satxyz2") map toRowSatxyz2 foreachRDD {
      _.toDF.write.mode("append").jdbc(jdbcUri, "rawdata.satxyz2", jdbcProps)
    }

    ssc.start()
    System.out.println("Start StreamingContext")
    ssc.awaitTermination()
    spark.close()
  }

  def printHelp(): Unit = {
    val usagestr = """
    Usage: <progname> <lat> <lon> <alt> <kafka_server> <clickhouse_server>
    <lat>                 - receiver latitude
    <lon>                 - receiver longitude
    <alt>                 - receiver altitude
    <kafka_server>        - Kafka server address:port, (string)
    <clickhouse_server>   - ClickHouse server (HTTP-interface) address:port, (string)
    """
    System.out.println(usagestr)
  }
}
