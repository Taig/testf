package com.ayendo.testf

import java.net.{HttpURLConnection, URL}

import cats.effect.IO

object WebsiteStatusTest extends TestF {
  def request(url: String): IO[Int] =
    IO.delay(new URL(url)).flatMap { url =>
      val open = IO.delay(url.openConnection().asInstanceOf[HttpURLConnection])
      val load =
        (connection: HttpURLConnection) => IO.delay(connection.getResponseCode)
      val disconnect =
        (connection: HttpURLConnection) => IO.delay(connection.disconnect())
      open.bracket(load)(disconnect)
    }

  val typelevel: Test[IO] =
    Test.label(
      "typelevel",
      Test.eval(
        request("https://typelevel.org/").map { code =>
          Test.assert(code == 200, "code != 200")
        }
      )
    )

  val scalaLang: Test[IO] =
    Test.label(
      "scala",
      Test.eval(
        request("https://www.scala-lang.org/").map { code =>
          Test.assert(code == 200, "code != 200")
        }
      )
    )

  val github: Test[IO] =
    Test.label(
      "github",
      Test.eval(
        request("https://github.com/").map { code =>
          Test.assert(code == 200, "code != 200")
        }
      )
    )

  override val suite: IO[Test[Pure]] =
    Compiler[IO].compile(
      Test.label(
        "WebsiteStatusTest",
        Test.of(typelevel, scalaLang, github)
      )
    )
}
