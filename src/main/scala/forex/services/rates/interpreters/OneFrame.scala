package forex.services.rates.interpreters

import cats.Applicative
import cats.effect.{ ConcurrentEffect, Resource }
import cats.implicits.{ catsSyntaxApplicativeError, catsSyntaxEitherId, toFunctorOps }
import com.github.benmanes.caffeine.cache.{ Cache, Caffeine }
import forex.config.OneFrameConfig
import forex.domain.Rate
import forex.http.rates.Protocol.responseDecoder
import forex.services.rates.Algebra
import forex.services.rates.Errors.Error.OneFrameLookupFailed
import forex.services.rates.Errors._
import org.http4s._
import org.http4s.circe.jsonOf
import org.http4s.client.Client
import org.http4s.blaze.client.BlazeClientBuilder
import org.typelevel.ci.CIString

import java.time.Duration
import scala.concurrent.ExecutionContext

class OneFrame[F[_]: ConcurrentEffect](config: OneFrameConfig) extends Algebra[F] {
  private val blazeClient: Resource[F, Client[F]]                = BlazeClientBuilder[F](ExecutionContext.global).resource
  private implicit val rateDecoder: EntityDecoder[F, List[Rate]] = jsonOf[F, List[Rate]]

  private val cache: Cache[Rate.Pair, List[Rate]] = Caffeine
    .newBuilder()
    .expireAfterWrite(Duration.ofSeconds(config.ttl.toSeconds))
    .build[Rate.Pair, List[Rate]]()

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    Option(cache.getIfPresent(pair)) match {
      case Some(v) =>
        Applicative[F].pure(Right(v.last))
      case None =>
        buildRequest(pair).map {
          case Right(value) =>
            cache.put(pair, value)
            Right(value.last)
          case Left(error) =>
            Left(error)
        }
    }

  def buildRequest(pair: Rate.Pair): F[Error Either List[Rate]] = {
    val uri = s"${config.url}/rates"

    val request = Request[F](
      method = Method.GET,
      uri = Uri.unsafeFromString(uri).withQueryParam("pair", s"${pair.from}${pair.to}"),
      headers = Headers(Header.Raw(CIString("token"), config.token))
    )

    blazeClient
      .use(client => client.expect[List[Rate]](request))
      .map(response => response.asRight[Error])
      .handleError(_ => OneFrameLookupFailed("Response Error").asLeft[List[Rate]])
  }

}
