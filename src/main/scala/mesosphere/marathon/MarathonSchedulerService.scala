package mesosphere.marathon

import org.apache.mesos.Protos.FrameworkInfo
import org.apache.mesos.MesosSchedulerDriver
import java.util.logging.Logger
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.MarathonStore
import com.google.common.util.concurrent.AbstractExecutionThreadService
import javax.inject.{Named, Inject}
import java.util.{TimerTask, Timer}
import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.Duration
import java.util.concurrent.atomic.AtomicBoolean
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.Group.JoinException
import scala.Option
import com.twitter.common.zookeeper.Candidate
import com.twitter.common.zookeeper.Candidate.Leader
import scala.util.Random
import mesosphere.mesos.util.FrameworkIdUtil

/**
 * Wrapper class for the scheduler
 *
 * @author Tobi Knaup
 */
class MarathonSchedulerService @Inject()(
    @Named(ModuleNames.NAMED_CANDIDATE) candidate: Option[Candidate],
    config: MarathonConf,
    @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean,
    store: MarathonStore[AppDefinition],
    frameworkIdUtil: FrameworkIdUtil,
    scheduler: MarathonScheduler)
  extends AbstractExecutionThreadService with Leader {

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global

  // Time to wait before trying to balance app tasks after driver starts
  val balanceWait = Duration(30, "seconds")

  val log = Logger.getLogger(getClass.getName)

  val frameworkName = "marathon-" + Main.properties.getProperty("marathon.version")

  val frameworkInfo = FrameworkInfo.newBuilder()
    .setName(frameworkName)
    .setFailoverTimeout(config.mesosFailoverTimeout())
    .setUser("") // Let Mesos assign the user
    .setCheckpoint(config.checkpoint())

  // Set the framework ID
  frameworkIdUtil.fetch() match {
    case Some(id) => {
      log.info(s"Setting framework ID to ${id.getValue}")
      frameworkInfo.setId(id)
    }
    case None => {
      log.info("No previous framework ID found")
    }
  }
  // Set the role, if provided.
  config.mesosRole.get.map(frameworkInfo.setRole)

  val driver = new MesosSchedulerDriver(scheduler, frameworkInfo.build, config.mesosMaster())

  var abdicateCmd: Option[ExceptionalCommand[JoinException]] = None

  def defaultWait = {
    store.defaultWait
  }

  def startApp(app: AppDefinition): Future[_] = {
    // Backwards compatibility
    if (app.ports == Nil) {
      val port = newAppPort(app)
      app.ports = Seq(port)
      log.info(s"Assigned port $port to app '${app.id}'")
    }

    scheduler.startApp(driver, app)
  }

  def stopApp(app: AppDefinition): Future[_] = {
    scheduler.stopApp(driver, app)
  }

  def scaleApp(app: AppDefinition, applyNow: Boolean = true): Future[_] = {
    scheduler.scaleApp(driver, app, applyNow)
  }

  def listApps(): Seq[AppDefinition] = {
    // TODO method is expensive, it's n+1 trips to ZK. Cache this?
    val names = Await.result(store.names(), defaultWait)
    val futures = names.map(name => store.fetch(name))
    val futureServices = Future.sequence(futures)
    Await.result(futureServices, defaultWait).map(_.get).toSeq
  }

  def getApp(appName: String): Option[AppDefinition] = {
    val future = store.fetch(appName)
    Await.result(future, defaultWait)
  }

  //Begin Service interface
  def run() {
    log.info("Starting up")
    if (leader.get) {
      runDriver()
    } else {
      offerLeaderShip()
    }
  }

  override def triggerShutdown() {
    log.info("Shutting down")
    abdicateCmd.map(_.execute)
    stopDriver()
  }

  def runDriver() {
    log.info("Running driver")
    scheduleTaskBalancing()
    driver.run()
  }

  def stopDriver() {
    log.info("Stopping driver")
    driver.stop(true) // failover = true
  }

  def isLeader = {
    leader.get() || getLeader.isEmpty
  }

  def getLeader: Option[String] = {
    if (candidate.nonEmpty && candidate.get.getLeaderData.isPresent) {
      return Some(new String(candidate.get.getLeaderData.get))
    }
    None
  }
  //End Service interface

  //Begin Leader interface, which is required for CandidateImpl.
  def onDefeated() {
    log.info("Defeated")
    leader.set(false)
    stopDriver()

    // Don't offer leadership if we're shutting down
    if (isRunning) {
      offerLeaderShip()
    }
  }

  def onElected(abdicate: ExceptionalCommand[JoinException]) {
    log.info("Elected")
    abdicateCmd = Some(abdicate)
    leader.set(true)
    runDriver()
  }
  //End Leader interface

  private def scheduleTaskBalancing() {
    val timer = new Timer()
    val task = new TimerTask {
      def run() {
        scheduler.balanceTasks(driver)
        scheduleTaskBalancing
      }
    }
    timer.schedule(task, balanceWait.toMillis)
  }

  private def offerLeaderShip() {
    if (candidate.nonEmpty) {
      log.info("Offering leadership.")
      candidate.get.offerLeadership(this)
    }
  }

  private def newAppPort(app: AppDefinition): Int = {
    // TODO this is pretty expensive, find a better way
    val assignedPorts = listApps().map(_.ports).flatten
    var port = 0
    do {
      port = config.localPortMin() + Random.nextInt(config.localPortMax() - config.localPortMin())
    } while (assignedPorts.contains(port))
    port
  }
}
