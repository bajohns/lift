package net.liftweb.util
import org.specs._
import org.specs.util.WaitFor
import org.specs.runner._
import scala.actors.Actor
import java.util.concurrent._

class ActorPingSpecTest extends JUnit3(ActorPingSpec)
object ActorPingSpecRunner extends ConsoleRunner(ActorPingSpec)
object ActorPingSpec extends Specification with PingedService with WaitFor {
  "The ActorPing object" should { doBefore { ActorPing.restart }
    "provide a schedule method to ping an actor regularly" in {
      service.start
      ActorPing.schedule(service, Alive, 10)
      waitFor(100.ms)
      service.pinged must beTrue
    }
  }
}
trait PingedService {
  case object Alive
  val service = new Service
  
  class Service extends Actor {
    var pinged = false
    def act() {
      while (true) {
        receive {
          case Alive => {pinged = true; exit()}
        }
      }
    }
  }
}
