package forex.http.rates

import cats.data.Kleisli
import cats.effect.{ ContextShift, IO }
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.http.rates.Protocol.rateDecoderForMocks
import forex.programs.RatesProgram
import forex.programs.rates.Errors.Error.{ RateLimitExceeded, RateLookupFailed }
import forex.programs.rates.Protocol.GetRatesRequest
import org.http4s.implicits._
import org.http4s.circe.jsonOf
import org.http4s.{ EntityDecoder, Method, Request, Response, Status }
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.ExecutionContext

class RatesHttpRoutesSpec extends AnyFlatSpec with Matchers {
  implicit val cs: ContextShift[IO]                           = IO.contextShift(ExecutionContext.global)
  implicit val getApiResponseDecoder: EntityDecoder[IO, Rate] = jsonOf[IO, Rate]
  val ratesHttpRoutes                                         = new RatesHttpRoutes[IO](RatesHttpRoutesSpec.mockRatesProgram)

  val httpApp: Kleisli[IO, Request[IO], Response[IO]] = ratesHttpRoutes.routes.orNotFound
  "RatesHttpRoutes" should "return correct 200 status and expected response" in {
    val request  = Request[IO](Method.GET, uri"/rates?from=USD&to=EUR")
    val response = httpApp.run(request).unsafeRunSync()

    response.status shouldBe Status.Ok
  }

  "RatesHttpRoutes" should "return expected response in case if both parameters are valid" in {
    val request      = Request[IO](Method.GET, uri"/rates?from=USD&to=EUR")
    val response     = httpApp.run(request).unsafeRunSync()
    val responseBody = response.as[Rate].unsafeRunSync()

    responseBody.from shouldBe Currency.USD
    responseBody.to shouldBe Currency.EUR
    responseBody.price shouldBe Price(0.71810472617368925)
  }

  "RatesHttpRoutes" should "return 500 status and custom message for RateLimitExceeded error" in {
    val request = Request[IO](Method.GET, uri"/rates?from=USD&to=EUR")
    val mockRatesProgram: RatesProgram[IO] = new RatesProgram[IO] {
      override def get(request: GetRatesRequest): IO[Either[RateLookupFailed, Rate]] =
        IO.raiseError(RateLimitExceeded("Rate limit exceeded"))
    }
    val routesWithMockProgram = new RatesHttpRoutes[IO](mockRatesProgram).routes.orNotFound
    val response              = routesWithMockProgram.run(request).unsafeRunSync()
    val responseBody          = response.as[String].unsafeRunSync()

    responseBody should include("The number of requests is limited:")
    response.status shouldBe Status.InternalServerError
  }

  "RatesHttpRoutes" should "return 400 status and custom message in case when user provide same type of Currency" in {
    val request = Request[IO](Method.GET, uri"/rates?from=EUR&to=EUR")

    val mockRatesProgram: RatesProgram[IO] = new RatesProgram[IO] {
      override def get(request: GetRatesRequest): IO[Either[RateLookupFailed, Rate]] =
        IO.raiseError(new RuntimeException("Runtime exception occurred"))
    }
    val routesWithMockProgram = new RatesHttpRoutes[IO](mockRatesProgram).routes.orNotFound

    val response     = routesWithMockProgram.run(request).unsafeRunSync()
    val responseBody = response.as[String].unsafeRunSync()

    response.status shouldBe Status.BadRequest
    responseBody should include("you can't exchange the same type of currency")
  }

  "RatesHttpRoutes" should "return 400 status and custom message in case when user provide invalid from parameter" in {
    val request      = Request[IO](Method.GET, uri"/rates?from=&to=EUR")
    val response     = httpApp.run(request).unsafeRunSync()
    val responseBody = response.as[String].unsafeRunSync()

    response.status shouldBe Status.BadRequest
    responseBody should include("Invalid 'from' parameter:")
  }

  "RatesHttpRoutes" should "return 400 status and custom message in case when user provide invalid to parameter" in {
    val request      = Request[IO](Method.GET, uri"/rates?from=USD&to=EUR11")
    val response     = httpApp.run(request).unsafeRunSync()
    val responseBody = response.as[String].unsafeRunSync()

    response.status shouldBe Status.BadRequest
    responseBody should include("Invalid 'to' parameter:")
  }

}

object RatesHttpRoutesSpec {
  val mockRatesProgram: RatesProgram[IO] = new RatesProgram[IO] {
    override def get(request: GetRatesRequest): IO[Either[RateLookupFailed, Rate]] = {
      val mockRate = Rate(
        from = request.from,
        to = request.to,
        bid = 0.6118225421857174,
        ask = 0.8243869101616611,
        price = Price(0.71810472617368925),
        timestamp = Timestamp(
          OffsetDateTime.parse("2024-03-03T17:57:06.151Z", DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        )
      )

      IO.pure(Right(mockRate))
    }
  }
}
