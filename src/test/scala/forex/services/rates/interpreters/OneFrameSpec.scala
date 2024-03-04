package forex.services.rates.interpreters

import cats.effect._
import cats.implicits.toTraverseOps
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import forex.config.OneFrameConfig
import forex.domain._
import forex.services.rates.Errors.Error.{ OneFrameLookupFailed, RateLimitExceeded }
import org.http4s.server.Server
import org.http4s.blaze.server.BlazeServerBuilder

import scala.concurrent.ExecutionContext

class OneFrameSpec extends AnyFlatSpec with Matchers {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]     = IO.timer(ExecutionContext.global)

  val fakeLimit       = 5
  val fakeRateLimiter = new FakeLimiter(fakeLimit)

  val testRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "rates" =>
      Ok(OneFrameSpec.mockRateResponse)
  }
  val testServer: Resource[IO, Server] = BlazeServerBuilder[IO](ExecutionContext.global)
    .bindHttp(10000, "localhost")
    .withHttpApp(testRoutes.orNotFound)
    .resource

  "OneFrame" should "build correct request with correct parameters" in {
    testServer
      .use { _ =>
        val oneFrame = new OneFrame[IO](OneFrameSpec.mockConfig)

        oneFrame.get(Rate.Pair(Currency.USD, Currency.EUR)).map {
          case Right(rate) =>
            rate.from should be(Currency.USD)
            rate.to should be(Currency.EUR)
          case Left(_) =>
            fail("Received an error instead of a rate")
        }
      }
  }

  "OneFrame" should "be correctly reported in case if the request was not valid" in {
    val errorRoutes = HttpRoutes.of[IO] {
      case GET -> Root / "rates" =>
        InternalServerError("Server error")
    }

    val errorServer: Resource[IO, Server] = BlazeServerBuilder[IO](ExecutionContext.global)
      .bindHttp(8080, "localhost")
      .withHttpApp(errorRoutes.orNotFound)
      .resource

    errorServer
      .use { _ =>
        val oneFrame = new OneFrame[IO](OneFrameSpec.mockConfig)

        oneFrame.get(Rate.Pair(Currency.USD, Currency.USD)).map {
          case Right(_) =>
            fail("Received a rate instead of an error")
          case Left(error) =>
            error shouldBe a[OneFrameLookupFailed]
        }
      }
  }

  "OneFrame" should "should use cache in case if user called the same request within 5 minutes" in {
    testServer
      .use { _ =>
        val oneFrame = new OneFrame[IO](OneFrameSpec.mockConfig)

        for {
          addedToCacheResult <- oneFrame.get(Rate.Pair(Currency.USD, Currency.EUR))
          timestamp <- addedToCacheResult match {
                        case Left(error) => IO.raiseError(new Exception(s"Unexpected error: $error"))
                        case Right(rate) => IO.pure(rate.timestamp)
                      }

          // should hit the cache and not create one more request
          firstResultFromCache <- oneFrame.get(Rate.Pair(Currency.USD, Currency.EUR))
          timestampFromCache <- firstResultFromCache match {
                                 case Left(error)          => IO.raiseError(new Exception(s"Unexpected error: $error"))
                                 case Right(rateFromCache) => IO.pure(rateFromCache.timestamp)
                               }

          _ <- IO(timestamp should be(timestampFromCache))

        } yield ()
      }
  }

  "OneFrame" should "limit the rate of requests" in {
    testServer.use { _ =>
      val oneFrame = new OneFrame[IO](OneFrameSpec.mockConfig)
      val pair     = Rate.Pair(Currency.USD, Currency.EUR)
      val results  = (0 until fakeLimit + 1).toList.traverse(_ => oneFrame.get(pair))

      results.map { res =>
        val successfulRequests = res.count(_.isRight)
        val failedRequests = res.count {
          case Left(RateLimitExceeded(_)) => true
          case _                          => false
        }

        successfulRequests should be(fakeLimit)
        failedRequests should be(1)
      }
    }
  }
}

object OneFrameSpec {
  val mockRateResponse: String =
    """
      |[
      |  {
      |    "from": "USD",
      |    "to": "EUR",
      |    "bid": 0.6118225421857174,
      |    "ask": 0.8243869101616611,
      |    "price": 0.71810472617368925,
      |    "timestamp": "2024-03-03T17:57:06.151Z"
      |  }
      |]
      |""".stripMargin

  val mockConfig: OneFrameConfig = OneFrameConfig(
    url = "http://localhost:8080",
    token = "10dc303535874aeccc86a8251e6992f5",
    ttl = scala.concurrent.duration.Duration.fromNanos(300000000000L),
    limit = 10000
  )

}

class FakeLimiter(val limit: Int) extends Limiter(limit) {
  private var _counter: Int                 = 0
  override def isLimited: Boolean           = _counter >= limit
  override val incrementCounter: () => Unit = () => _counter += 1
}
