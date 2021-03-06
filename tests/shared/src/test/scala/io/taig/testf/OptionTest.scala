package io.taig.testf

import cats.effect.IO
import cats.implicits._
import cats.laws.discipline.MonadTests
import io.taig.testf.dsl._

object OptionTest extends IOTestApp {
  val monadLaws: Assertion[Pure] =
    verify(
      "MonadLaws",
      MonadTests[Option].monad[Int, Int, String]
    )

  override val suite: IO[Assertion[Pure]] =
    test("OptionTest")(monadLaws).interpret[IO]
}
