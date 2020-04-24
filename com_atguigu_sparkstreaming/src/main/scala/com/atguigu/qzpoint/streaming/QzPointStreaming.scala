package com.atguigu.qzpoint.streaming

import java.lang
import java.sql.{Connection, ResultSet}
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

import com.atguigu.qzpoint.bean.LearnModel
import com.atguigu.qzpoint.util.{DataSourceUtil, QueryCallback, SqlProxy}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.kafka010._
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.collection.mutable

/**
  *
  *
  * 知识点掌握度实时统计
  *
  *
  *     在离线数仓当中，task个数是cpu的2-3倍数，而在SparkStreaming当中，实时当中，task个数跟cpu个数是1:1的。
  *      就是说我现在kafka分区个数是10个。那我相应的cpu个数据就应该是10个。如果我分配了超过10个cpu的话，都是空算的。实际在处理数据的只有10个
  *      这样的话就会导致。资源没有合理利用。
  *      sparkstreaming当中是不会使用coalease和repartition这两个算子去改变分区的。两者去改变的话，都会降低速度的。coalease会减小并行度。
  *      repartition的会造成shuffle。也会增大延迟。sparkstream分区各数一般设置好了就不会再去改变的。
  *
  *      如果有多个spark任务在跑的话，我们在linux下jps 是分布清哪个是我们的任务的。
  *      需要通过
  *      ps -ef | grep QzPointStreaming （我们运行的这个任务类名），这样就能找到我们这个任务的pid，进程id。然后在用这个java的命令对这个进程堆内存
  *      进行内存分析，
  *      jmap -heap 6860
  */
object QzPointStreaming {

  private val groupid = "qz_point_group"

  val map = new mutable.HashMap[String, LearnModel]()

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setAppName(this.getClass.getSimpleName)
      //            .setMaster("local[*]")
      .set("spark.streaming.kafka.maxRatePerPartition", "100")            //如果kafka端有数据积压，只能增加分区数和对应的消费者，然后这个参数也可以往上调。
      .set("spark.streaming.backpressure.enabled", "true")               //被压是解决spark这边数据积压的问题的。不是解决kakfa积压的。
      .set("spark.streaming.stopGracefullyOnShutdown", "true")
    val ssc = new StreamingContext(conf, Seconds(3))
    val topics = Array("qz_log")
    val kafkaMap: Map[String, Object] = Map[String, Object](
      "bootstrap.servers" -> "hadoop001:9092,hadoop002:9092,hadoop003:9092",
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> groupid,
      "auto.offset.reset" -> "earliest",
      "enable.auto.commit" -> (false: lang.Boolean)
    )
    //查询mysql中是否存在偏移量
    val sqlProxy = new SqlProxy()
    val offsetMap = new mutable.HashMap[TopicPartition, Long]()
    val client = DataSourceUtil.getConnection
    try {
      sqlProxy.executeQuery(client, "select * from `offset_manager` where groupid=?", Array(groupid), new QueryCallback {
        override def process(rs: ResultSet): Unit = {
          while (rs.next()) {
            val model = new TopicPartition(rs.getString(2), rs.getInt(3))
            val offset = rs.getLong(4)
            offsetMap.put(model, offset)
          }
          rs.close() //关闭游标
        }
      })
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      sqlProxy.shutdown(client)
    }
    //设置kafka消费数据的参数  判断本地是否有偏移量  有则根据偏移量继续消费 无则重新消费
    val stream: InputDStream[ConsumerRecord[String, String]] = if (offsetMap.isEmpty) {
      KafkaUtils.createDirectStream(
        ssc, LocationStrategies.PreferConsistent, ConsumerStrategies.Subscribe[String, String](topics, kafkaMap))
    } else {
      KafkaUtils.createDirectStream(
        ssc, LocationStrategies.PreferConsistent, ConsumerStrategies.Subscribe[String, String](topics, kafkaMap, offsetMap))
    }
    //过滤不正常数据 获取数据
    val dsStream = stream.filter(item => item.value().split("\t").length == 6).
      mapPartitions(partition => partition.map(item => {
        val line = item.value()
        val arr = line.split("\t")
        val uid = arr(0) //用户id
        val courseid = arr(1) //课程id
        val pointid = arr(2) //知识点id
        val questionid = arr(3) //题目id
        val istrue = arr(4) //是否正确
        val createtime = arr(5) //创建时间
        (uid, courseid, pointid, questionid, istrue, createtime)
      }))
    dsStream.foreachRDD(rdd => {
      //获取相同用户 同一课程 同一知识点的数据    提前来一个次预聚合，避免线程不安全问题。也可以用分布式锁来实现。
      val groupRdd = rdd.groupBy(item => item._1 + "-" + item._2 + "-" + item._3)
      groupRdd.foreachPartition(partition => {
        //在分区下获取jdbc连接
        val sqlProxy = new SqlProxy()
        val client = DataSourceUtil.getConnection
        try {
          partition.foreach { case (key, iters) =>
            qzQuestionUpdate(key, iters, sqlProxy, client) //对题库进行更新操作
          }
        } catch {
          case e: Exception => e.printStackTrace()
        }
        finally {
          sqlProxy.shutdown(client)
        }
      }
      )
    })
    //处理完 业务逻辑后 手动提交offset维护到本地 mysql中
    stream.foreachRDD(rdd => {
      val sqlProxy = new SqlProxy()
      val client = DataSourceUtil.getConnection    //driver 端获取的。
      try {
        val offsetRanges: Array[OffsetRange] = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
        for (or <- offsetRanges) {
          sqlProxy.executeUpdate(client, "replace into `offset_manager` (groupid,topic,`partition`,untilOffset) values(?,?,?,?)",
            Array(groupid, or.topic, or.partition.toString, or.untilOffset))
        }

       /*   模拟内存泄露的情况
       for (i <- 0 until 100000) {
          val model = new LearnModel(1, 1, 1, 1, 1, 1, "", 2, 1l, 1l, 1, 1)
          map.put(UUID.randomUUID().toString, model)
        }*/
      } catch {
        case e: Exception => e.printStackTrace()
      } finally {
        sqlProxy.shutdown(client)
      }
    })
    ssc.start()
    ssc.awaitTermination()
  }

  /**
    * 对题目表进行更新操作
    *
    * @param key
    * @param iters
    * @param sqlProxy
    * @param client
    * @return
    */
  def qzQuestionUpdate(key: String, iters: Iterable[(String, String, String, String, String, String)], sqlProxy: SqlProxy, client: Connection) = {
    val keys = key.split("-")
    val userid = keys(0).toInt
    val courseid = keys(1).toInt
    val pointid = keys(2).toInt
    val array = iters.toArray
    val questionids = array.map(_._4).distinct //对当前批次的数据下questionid 去重
    //查询历史数据下的 questionid
    var questionids_history: Array[String] = Array()
    sqlProxy.executeQuery(client, "select questionids from qz_point_history where userid=? and courseid=? and pointid=?",
      Array(userid, courseid, pointid), new QueryCallback {
        override def process(rs: ResultSet): Unit = {
          while (rs.next()) {
            questionids_history = rs.getString(1).split(",")
          }
          rs.close() //关闭游标
        }
      })
    //获取到历史数据后再与当前数据进行拼接 去重
    //val resultQuestionid: Array[String] = questionids.distinct.union(questionids_history).distinct  这种方式效率更高。
    val resultQuestionid = questionids.union(questionids_history).distinct
    val countSize = resultQuestionid.length   //得到该用户在该课程下该知识点下，截止当前批次（包括当前批次在内的）题目id（去重之后）个数
    val resultQuestionid_str = resultQuestionid.mkString(",")
    val qz_count = questionids.length //去重后的题个数，当前批次去重（题目id）之后的个数据。
    var qz_sum = array.length //获取当前批次题总数
    var qz_istrue = array.filter(_._5.equals("1")).size //取当前批获次做正确的题个数
    val createtime = array.map(_._6).min //获取最早的创建时间 作为表中创建时间
    //更新qz_point_set 记录表 此表用于存当前用户做过的questionid表
    val updatetime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now())
    sqlProxy.executeUpdate(client, "insert into qz_point_history(userid,courseid,pointid,questionids,createtime,updatetime) values(?,?,?,?,?,?) " +
      " on duplicate key update questionids=?,updatetime=?", Array(userid, courseid, pointid, resultQuestionid_str, createtime, createtime, resultQuestionid_str, updatetime))

    var qzSum_history = 0
    var istrue_history = 0
    sqlProxy.executeQuery(client, "select qz_sum,qz_istrue from qz_point_detail where userid=? and courseid=? and pointid=?",
      Array(userid, courseid, pointid), new QueryCallback {
        override def process(rs: ResultSet): Unit = {
          while (rs.next()) {
            qzSum_history += rs.getInt(1)
            istrue_history += rs.getInt(2)
          }
          rs.close()
        }
      })
    qz_sum += qzSum_history
    qz_istrue += istrue_history
    val correct_rate = qz_istrue.toDouble / qz_sum.toDouble //计算正确率
    //计算完成率
    //假设每个知识点下一共有30道题  先计算题的做题情况 再计知识点掌握度
    val qz_detail_rate = countSize.toDouble / 30 //算出做题情况乘以 正确率 得出完成率 假如30道题都做了那么正确率等于 知识点掌握度
    val mastery_rate = qz_detail_rate * correct_rate
    sqlProxy.executeUpdate(client, "insert into qz_point_detail(userid,courseid,pointid,qz_sum,qz_count,qz_istrue,correct_rate,mastery_rate,createtime,updatetime)" +
      " values(?,?,?,?,?,?,?,?,?,?) on duplicate key update qz_sum=?,qz_count=?,qz_istrue=?,correct_rate=?,mastery_rate=?,updatetime=?",
      Array(userid, courseid, pointid, qz_sum, countSize, qz_istrue, correct_rate, mastery_rate, createtime, updatetime, qz_sum, countSize, qz_istrue, correct_rate, mastery_rate, updatetime))

  }
}
