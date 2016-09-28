package org.vivint.ceph

import akka.actor.{ ActorRef, ActorSystem, Props }
import org.apache.curator.framework.{ CuratorFramework, CuratorFrameworkFactory }
import org.apache.mesos.MesosSchedulerDriver
import org.apache.mesos.Protos._
import org.vivint.ceph.kvstore.KVStore
import scala.concurrent.Future
import scaldi.Module

trait ZookeeperModule extends Module {
  private val appConfiguration = inject[AppConfiguration]

  import org.apache.curator.retry.ExponentialBackoffRetry
  private val retryPolicy = new ExponentialBackoffRetry(1000, 3)

  bind [CuratorFramework] to CuratorFrameworkFactory.builder.
    connectString(appConfiguration.zookeeper).
    namespace("ceph-on-mesos").
    retryPolicy(retryPolicy).
    build()
}

class Configuration(args: List[String]) extends Module {
  bind [AppConfiguration] to AppConfiguration.fromArgs(args.toList)
}

trait FrameworkModule extends Module {

  bind [FrameworkInfo] to {
    val options = inject[AppConfiguration]
    val kvStore = inject[KVStore]

    val frameworkBuilder = FrameworkInfo.newBuilder().
      setUser("").
      setName(options.name).
      setCheckpoint(true)

    options.principal.foreach(frameworkBuilder.setPrincipal)

    frameworkBuilder.build()
  }
}

class Universe(args: List[String]) extends Configuration(args) with Module with ZookeeperModule /*with FrameworkModule*/ {
  implicit val system = ActorSystem("ceph-on-mesos")
  bind [KVStore] to (new kvstore.ZookeeperStore(inject[CuratorFramework])(zookeeperDispatcher))
  bind [ActorSystem] to system
  bind [Option[Credential]] to {
    val options = inject[AppConfiguration]
    for {
      principal <- options.principal
      secret <- options.secret
    } yield {
      Credential.newBuilder().
        setPrincipal(principal).
        setSecret(secret).
        build()
    }
  }

  val zookeeperDispatcher = system.dispatchers.lookup("zookeeper-dispatcher")

  bind [ActorRef] identifiedBy (classOf[TaskActor]) to {
    system.actorOf(Props(new TaskActor), "framework-actor")
  }

  bind [ActorRef] identifiedBy (classOf[FrameworkActor]) to {
    system.actorOf(Props(new FrameworkActor), "framework-actor")
  }
}

object Main extends App {
  val module = new Universe(args.toList)
  import module.injector
  import scaldi.Injectable._

  implicit val actorSystem = inject[ActorSystem]

  val taskActor = inject[ActorRef](classOf[TaskActor])
  val frameworkActor = inject[ActorRef](classOf[FrameworkActor])
}
