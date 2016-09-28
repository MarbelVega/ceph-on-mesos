package org.vivint.ceph

import java.util.UUID
import org.apache.mesos.Protos.TaskStatus
import org.vivint.ceph.kvstore.KVStore
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import org.apache.mesos.Protos
import scala.async.Async.{async, await}
import java.nio.charset.StandardCharsets.UTF_8

case class TaskStore(kvStore: KVStore) {
  private val tasksPath = "tasks"
  import ExecutionContext.Implicits.global

  import model._

  import play.api.libs.json._
  import PlayJsonFormats._
  private val parsingFunction: PartialFunction[String, (String, (JsValue => CephNode))] = {
    case path if path.startsWith("mon:") =>
      (path, _.as[MonNode])
  }

  def getNodes: Future[Seq[CephNode]] = async {
    val paths = await(kvStore.children(tasksPath)).
      collect(parsingFunction).
      map { case (path, parser) =>
        (tasksPath + "/" + path, parser)
      }

    await(kvStore.getAll(paths.map(_._1))).
      zip(paths.map(_._2)).
      map { case (optBytes, parser) =>
        optBytes.map { bytes =>
          (parser(Json.parse(new String(bytes, UTF_8))))
        }
      }.
      flatten
  }

  def getTasks: Future[Seq[Protos.TaskStatus]] = async {
    val paths = await(kvStore.children(tasksPath)).
      filter(_.startsWith("task:")).
      map { p => tasksPath + "/" + p }

    val children = await(kvStore.getAll(paths))
    children.flatten.map(Protos.TaskStatus.parseFrom)
  }

  def updateTask(taskStatus: Protos.TaskStatus): Future[Unit] = {
    kvStore.set(
      tasksPath + "/" + taskStatus.getTaskId.getValue,
      taskStatus.toByteArray())
  }

  def deleteTaskId(taskId: String): Future[Unit] = {
    kvStore.delete(tasksPath + "/" + taskId)
  }


}
