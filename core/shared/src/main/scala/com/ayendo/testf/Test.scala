package com.ayendo.testf

import cats._
import cats.data.Validated
import cats.effect.IO
import cats.implicits._

sealed trait Test[F[_], +A] extends Product with Serializable {
  def mapK[G[_]](f: F ~> G)(implicit F: Functor[F]): Test[G, A] =
    Test.mapK(this)(f)

  def liftIO(implicit F: Functor[F], L: LiftIO[F]): Test[IO, A] = mapK(L.lift)

  def compile(implicit F: Monad[F]): F[Report] = Test.compile(this)
}

object Test extends Test1 with TestBuilders {
  final case class Error[F[_]](message: String) extends Test[F, Nothing]

  final case class Failure[F[_]](throwable: Throwable) extends Test[F, Nothing]

  final case class Group[F[_], A](tests: List[Test[F, A]]) extends Test[F, A]

  final case class Label[F[_], A](description: String, test: Test[F, A])
      extends Test[F, A]

  final case class Result[F[_]](report: Report) extends Test[F, Nothing]

  final case class Skip[F[_], A](test: Test[F, A]) extends Test[F, A]

  final case class Success[F[_], A](value: A) extends Test[F, A]

  final case class Suspend[F[_], A](test: F[Test[F, A]]) extends Test[F, A]

  implicit val monadId: Monad[Test[Id, ?]] = monad[Id]

  implicit def semigroup[F[_], A]: Semigroup[Test[F, A]] = {
    case (Group(xs), Group(ys)) => Group(xs |+| ys)
    case (Group(xs), y)         => Group(xs :+ y)
    case (x, Group(ys))         => Group(x +: ys)
    case (x, y)                 => Group(List(x, y))
  }

  implicit def eq[A: Eq]: Eq[Test[Id, A]] = new Eq[Test[Id, A]] {
    override def eqv(x: Test[Id, A], y: Test[Id, A]): Boolean = {
      PartialFunction.cond((x, y)) {
        case (Error(m1), Error(m2))       => m1 === m2
        case (Failure(t1), Failure(t2))   => t1 == t2
        case (Group(xs), Group(ys))       => xs === ys
        case (Label(dx, x), Label(dy, y)) => dx === dy && eqv(x, y)
        case (Result(rx), Result(ry))     => rx === ry
        case (Skip(x), Skip(y))           => eqv(x, y)
        case (Success(x), Success(y))     => x === y
        case (Suspend(x), Suspend(y))     => eqv(x, y)
      }
    }
  }

  implicit def show[F[_], A: Show]: Show[Test[F, A]] = new Show[Test[F, A]] {
    override def show(test: Test[F, A]): String = test match {
      case Error(message)           => s"Error($message)"
      case Failure(throwable)       => s"Failure(${throwable.getMessage})"
      case Group(tests)             => s"Group(${tests.map(show).mkString(", ")})"
      case Label(description, test) => s"Label($description, ${show(test)})"
      case Result(report)           => show"Result($report)"
      case Skip(test)               => s"Skip(${show(test)})"
      case Success(value)           => show"Success($value)"
      case Suspend(test)            => s"Defer($test)"
    }
  }

  def mapK[F[_], G[_], A](test: Test[F, A])(f: F ~> G)(
      implicit F: Functor[F]): Test[G, A] = test match {
    case error: Error[F]          => error.asInstanceOf[Error[G]]
    case failure: Failure[F]      => failure.asInstanceOf[Failure[G]]
    case Group(tests)             => Group(tests.map(_.mapK(f)))
    case Label(description, test) => Label(description, test.mapK(f))
    case result: Result[F]        => result.asInstanceOf[Result[G]]
    case Skip(test)               => Skip(test.mapK(f))
    case success: Success[F, A]   => success.asInstanceOf[Test[G, A]]
    case Suspend(test)            => Suspend(f(test.map(_.mapK(f))))
  }

  def compile[F[_], A](test: Test[F, A])(implicit F: Monad[F]): F[Report] =
    test match {
      case Error(message) => F.pure(Report.Message(Report.Error, message))
      case Failure(throwable) =>
        F.pure(Report.Stacktrace(Report.Failure, throwable))
      case Label(description, test) =>
        compile(test).map(Report.Label(_, description))
      case Group(tests)   => tests.traverse(_.compile).map(Report.Group)
      case Result(report) => F.pure(report)
      case Skip(_)        => F.pure(Report.Skip)
      case Success(_)     => F.pure(Report.Success)
      case Suspend(test)  => test.flatMap(_.compile)
    }

  implicit def testOps[F[_], A](test: Test[F, A]): TestOps[F, A] =
    new TestOps(test)

  implicit def testOpsBoolean[F[_]](test: Test[F, Boolean]): TestOpsBoolean[F] =
    new TestOpsBoolean(test)

  implicit def testOpsMonoid[F[_], A](test: Test[F, A]): TestOpsMonoid[F, A] =
    new TestOpsMonoid[F, A](test)

  implicit def testOpsValidated[F[_], A, B](
      test: Test[F, Validated[A, B]]): TestOpsValidated[F, A, B] =
    new TestOpsValidated[F, A, B](test)

  implicit def testOpsString[F[_]](test: Test[F, String]): TestOpsString[F] =
    new TestOpsString(test)
}

trait Test1 {
  implicit def monad[F[_]: Functor]: Monad[Test[F, ?]] = new Monad[Test[F, ?]] {
    override def pure[A](x: A): Test[F, A] = Test.Success(x)

    override def map[A, B](fa: Test[F, A])(f: A => B): Test[F, B] =
      fa match {
        case error: Test.Error[F]     => error
        case failure: Test.Failure[F] => failure
        case Test.Group(tests)        => Test.Group(tests.map(map(_)(f)))
        case Test.Label(description, test) =>
          Test.Label(description, map(test)(f))
        case result: Test.Result[F] => result
        case Test.Skip(test)        => Test.Skip(map(test)(f))
        case Test.Success(value)    => Test.Success(f(value))
        case Test.Suspend(test)     => Test.Suspend(test.map(map(_)(f)))
      }

    override def flatMap[A, B](fa: Test[F, A])(f: A => Test[F, B]): Test[F, B] =
      fa match {
        case error: Test.Error[F]     => error
        case failure: Test.Failure[F] => failure
        case Test.Group(tests)        => Test.Group(tests.map(flatMap(_)(f)))
        case Test.Label(description, test) =>
          Test.Label(description, flatMap(test)(f))
        case result: Test.Result[F] => result
        case Test.Skip(test)        => Test.Skip(flatMap(test)(f))
        case Test.Success(value)    => f(value)
        case Test.Suspend(test)     => Test.Suspend(test.map(flatMap(_)(f)))
      }

    override def tailRecM[A, B](a: A)(
        f: A => Test[F, Either[A, B]]): Test[F, B] = {
      def go(test: Test[F, Either[A, B]]): Test[F, B] = test match {
        case error: Test.Error[F]          => error
        case failure: Test.Failure[F]      => failure
        case Test.Group(tests)             => Test.Group(tests.map(go))
        case Test.Label(description, test) => Test.Label(description, go(test))
        case result: Test.Result[F]        => result
        case Test.Skip(test)               => Test.Skip(go(test))
        case Test.Success(Right(b))        => Test.Success(b)
        case Test.Success(Left(a))         => go(f(a))
        case Test.Suspend(test)            => Test.Suspend(test.map(go))
      }

      go(f(a))
    }
  }
}
