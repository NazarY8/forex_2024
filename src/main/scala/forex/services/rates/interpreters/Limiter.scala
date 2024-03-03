package forex.services.rates.interpreters

import java.time.Duration

private[interpreters] case class Limiter(private val limit: Int) {
  private var counter: Int         = 0
  private var lastResetTime: Long  = System.currentTimeMillis()
  val incrementCounter: () => Unit = () => counter += 1

  def isLimited: Boolean = {
    val currentTime = System.currentTimeMillis()
    val elapsedTime = currentTime - lastResetTime

    if (elapsedTime >= Duration.ofDays(1).toMillis) {
      counter = 0
      lastResetTime = currentTime
    }

    counter >= limit
  }
}
