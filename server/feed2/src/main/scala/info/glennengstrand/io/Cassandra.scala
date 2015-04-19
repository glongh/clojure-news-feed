package info.glennengstrand.io

import com.datastax.driver.core._
import java.util.logging.Logger
import java.util.Date

/**
 * Created by glenn on 2/28/15.
 * http://www.datastax.com/documentation/developer/java-driver/1.0/java-driver/quick_start/qsSimpleClientAddSession_t.html
 */

object Cassandra {
  val log = Logger.getLogger("info.glennengstrand.io.Cassandra")
  val sql: scala.collection.mutable.Map[String, PreparedStatement] = scala.collection.mutable.Map()

  def getConsistencyLevel(level: String): ConsistencyLevel = {
    level match {
      case "one" => ConsistencyLevel.ONE
      case "quorum" => ConsistencyLevel.LOCAL_QUORUM
      case _ => ConsistencyLevel.ANY
    }
  }
  def getCluster: Cluster = {
    // TODO: host could also be a comma delimiter list of server and port
    val b = Cluster.builder().addContactPoint(IO.settings.get(IO.nosqlHost).asInstanceOf[String])
    b.build()
  }
  def getKey(operation: String, entity: String): String = {
    operation + ":" + entity
  }
  lazy val cluster = getCluster
  lazy val session = cluster.connect(IO.settings.get(IO.nosqlKeyspace).asInstanceOf[String])
  lazy val ttl = IO.settings.get(IO.nosqlTimeToLiveInSeconds).asInstanceOf[String]
  def generateSelectFieldName(fn: String, ft: String): String = {
    ft match {
      case "Date" => "dateOf(" + fn + ")"
      case _ => fn
    }
  }
  def prepareFetch(operation: String, o: PersistentDataStoreBindings): PreparedStatement = {
    val key = getKey(operation, o.entity)
    sql.contains(key) match {
      case false => {
        sql.synchronized {
          sql.contains(key) match {
            case false => {
              val sl = for ((fn, ft) <- o.fetchOutputs) yield generateSelectFieldName(fn, ft)
              val wl = for (f <- o.fetchInputs) yield  f + " = ?"
              val fo = o.fetchOrder
              val ol = for (k <- fo.keys) yield k + " " + fo.get(k).getOrElse("desc")
              val select = "select " + sl.reduce(_ + ", " + _) + " from " + o.entity + " where " + wl.reduce(_ + " and " + _) + " order by " + ol.reduce(_ + ", " + _)
              log.warning(select)
              val stmt = session.prepare(select)
              val cl = getConsistencyLevel(IO.settings.get(IO.nosqlReadConsistencyLevel).asInstanceOf[String])
              stmt.setConsistencyLevel(cl)
              sql.put(operation, stmt)
              stmt
            }
            case true => sql.get(key).get
          }
        }
      }
      case true => sql.get(key).get
    }
  }
  def generateUpsertFieldValue(fieldName: String, o: PersistentDataStoreBindings): String = {
    o.getTypeOf(fieldName) match {
      case "Date" => "minTimeuuid(?)"
      case _ => "?"
    }
  }
  def prepareUpsert(operation: String, o: PersistentDataStoreBindings): PreparedStatement = {
    val key = getKey(operation, o.entity)
    sql.contains(key) match {
      case false => {
        sql.synchronized {
          sql.contains(key) match {
            case false => {
              val vl = for (f <- o.upsertInputs) yield generateUpsertFieldValue(f, o)
              val fl = for (f <- o.upsertInputs) yield  f
              val upsert = "insert into " + o.entity + "("  + fl.reduce(_ + ", " + _) + ") values (" + vl.reduce(_ + ", " + _) + ")" + " using ttl " + ttl
              log.warning(upsert)
              val stmt = session.prepare(upsert)
              val cl = getConsistencyLevel(IO.settings.get(IO.nosqlReadConsistencyLevel).asInstanceOf[String])
              stmt.setConsistencyLevel(cl)
              sql.put(operation, stmt)
              stmt
            }
            case true => sql.get(key).get
          }
        }
      }
      case true => sql.get(key).get
    }
  }
  def setBinding(binding: BoundStatement, fieldName: String, state: Map[String, Any], index: Int): Unit = {
    log.warning("setting " + fieldName + " parameter " + index + " to " + state.get(fieldName).getOrElse(0))
    state.get(fieldName).get match {
      case l: Long => binding.setLong(index, l)
      case i: Int => binding.setInt(index, i)
      case s: String => binding.setString(index, s)
      case d: Date => binding.setDate(index, d)
      case None =>
    }
  }
  def mapResults(retVal: scala.collection.mutable.Map[String, Any], r: Row, field: Tuple2[String, String]): Unit = {
    val fn = field._1.asInstanceOf[String]
    field._2 match {
      case "Long" => retVal.put(fn, r.getLong(fn))
      case "Int" => retVal.put(fn, r.getInt(fn))
      case "Date" => retVal.put(fn, r.getDate(fn))
      case _ => retVal.put(fn, r.getString(fn))
    }
  }
  def tupleFromRow(f: Tuple2[String, String], r: Row): Tuple2[String, Any] = {
    f._2 match {
      case "Long" => (f._1, r.getLong(f._1))
      case "Int" => (f._1, r.getInt(f._1))
      case "Date" => (f._1, r.getDate("dateOf(" + f._1 + ")"))
      case _ => (f._1, r.getString(f._1))
    }
  }
  def bindInputs(stmt: PreparedStatement, binding: Iterable[String], bound: Map[String, Any]): BoundStatement = {
    val retVal = new BoundStatement(stmt)
    var index = 0
    binding.foreach(f => {
      setBinding(retVal, f, bound, index)
      index += 1
    })
    retVal
  }
  def bindSingleRowOutputs(binding: BoundStatement, o: PersistentDataStoreBindings): Map[String, Any] = {
    val b = binding.bind()
    log.warning(b.toString())
    val rs = Cassandra.session.execute(b)
    var index = 1
    val r = rs.one()
    o.fetchOutputs.map(f => {
      tupleFromRow(f, r)
    }).toMap
  }
  def bindMultiRowOutputs(binding: BoundStatement, o: PersistentDataStoreBindings): Iterable[Map[String, Any]] = {
    val rs = Cassandra.session.execute(binding.bind())
    val r = rs.iterator()
    new Iterator[Map[String, Any]] {
      def hasNext = r.hasNext
      def next() = {
        val row = r.next()
        o.fetchOutputs.map(f => {
          tupleFromRow(f, row)
        }).toMap
      }
    }.toStream
  }
}

class CassandraReader extends PersistentDataStoreReader {
  val fetch: String = "Fetch"

  def read(o: PersistentDataStoreBindings, criteria: Map[String, Any]): Iterable[Map[String, Any]] = {
    val stmt = Cassandra.prepareFetch(fetch, o)
    val binding = Cassandra.bindInputs(stmt, o.fetchInputs, criteria)
    Cassandra.bindMultiRowOutputs(binding, o)
  }
}

trait CassandraWriter extends PersistentDataStoreWriter {
  val upsert: String = "Upsert"
  def write(o: PersistentDataStoreBindings, state: Map[String, Any], criteria: Map[String, Any]): Map[String, Any] = {
    val stmt = Cassandra.prepareUpsert(upsert, o)
    val binding = Cassandra.bindInputs(stmt, o.upsertInputs, state)
    val b = binding.bind()
    Cassandra.session.execute(b)
    state
  }
}
