package forex.services.rates

import cats.effect.ConcurrentEffect
import forex.config.OneFrameConfig
import interpreters._

object Interpreters {
  //def dummy[F[_]: Applicative]: Algebra[F] = new OneFrameDummy[F]()

  def oneFrame[F[_] : ConcurrentEffect](config: OneFrameConfig): Algebra[F] = new OneFrame[F](config)
}
