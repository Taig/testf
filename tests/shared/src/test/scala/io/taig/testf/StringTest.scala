package io.taig.testf

import cats.effect.IO
import cats.implicits._
import io.taig.testf.dsl._
import org.scalacheck.Gen

object StringTest extends IOTestApp {
  val start: Assertion[Pure] =
    test("startsWith") {
      check2(Gen.alphaNumStr, Gen.alphaNumStr) { (a, b) =>
        startsWith(a)(a + b)
      }
    }

  val concatenate: Assertion[Pure] =
    test("concatenate") {
      check2(Gen.alphaNumStr, Gen.alphaNumStr) { (a, b) =>
        test("lengthA")(isGte(a.length)((a + b).length)) &
          test("lengthB")(isGte(b.length)((a + b).length))
      }
    }

  val substring: Assertion[Pure] =
    test("substring") {
      check3(
        Gen.alphaNumStr,
        Gen.alphaNumStr,
        Gen.alphaNumStr
      ) { (a, b, c) =>
        isEqual(b)((a + b + c).substring(a.length, a.length + b.length))
      }
    }

  override val suite: IO[Assertion[Pure]] =
    test("StringTest")(start, concatenate, substring).interpret[IO]
}
