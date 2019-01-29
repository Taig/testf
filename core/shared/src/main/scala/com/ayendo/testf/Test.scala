package com.ayendo.testf

import cats._
import cats.data.Validated
import cats.effect.IO
import cats.implicits._

sealed trait Test[F[_], A] extends Product with Serializable {
  def mapK[G[_]](f: F ~> G)(implicit F: Functor[F]): Test[G, A] = this match {
    case error: Test.Error[F, A]       => error.asInstanceOf[Test.Error[G, A]]
    case failure: Test.Failure[F, A]   => failure.asInstanceOf[Test.Failure[G, A]]
    case Test.Group(tests)             => Test.Group(tests.map(_.mapK(f)))
    case Test.Label(description, test) => Test.Label(description, test.mapK(f))
    case pure: Test.Pure[F, A]         => pure.asInstanceOf[Test[G, A]]
    case Test.Skip(test)               => Test.Skip(test.mapK(f))
    case success: Test.Success[F, A]   => success.asInstanceOf[Test.Success[G, A]]
    case Test.Suspend(test)            => Test.Suspend(f(test.map(_.mapK(f))))
  }

  def liftIO(implicit F: Functor[F], L: LiftIO[F]): Test[IO, A] = mapK(L.lift)

  def compile(implicit F: Monad[F]): F[Report] = this match {
    case Test.Error(message)     => F.pure(Report.Error("error", message))
    case Test.Failure(throwable) => F.pure(Report.Failure("failure", throwable))
    case Test.Group(tests) =>
      tests.traverse(_.compile).map(Report.Group(_, description = None))
    case Test.Label(description, test) =>
      test.compile.map(_.withDescription(description))
    case Test.Pure(_)       => F.pure(Report.Success("pure"))
    case Test.Skip(_)       => F.pure(Report.Skip("skip"))
    case Test.Success()     => F.pure(Report.Success("success"))
    case Test.Suspend(test) => test.flatMap(_.compile)
  }
}

object Test {
  final case class Error[F[_], A](message: String) extends Test[F, A]

  final case class Failure[F[_], A](throwable: Throwable) extends Test[F, A]

  final case class Group[F[_], A](tests: List[Test[F, A]]) extends Test[F, A]

  final case class Label[F[_], A](description: String, test: Test[F, A])
      extends Test[F, A]

  final case class Pure[F[_], A](value: A) extends Test[F, A]

  final case class Skip[F[_], A](test: Test[F, A]) extends Test[F, A]

  final case class Success[F[_], A]() extends Test[F, A]

  final case class Suspend[F[_], A](test: F[Test[F, A]]) extends Test[F, A]

  implicit def monad[F[_]: Functor]: Monad[Test[F, ?]] = new Monad[Test[F, ?]] {
    override def pure[A](x: A): Test[F, A] = Pure(x)

    override def map[A, B](fa: Test[F, A])(f: A => B): Test[F, B] =
      fa match {
        case error: Error[F, A]       => error.asInstanceOf[Error[F, B]]
        case failure: Failure[F, A]   => failure.asInstanceOf[Failure[F, B]]
        case Group(tests)             => Group(tests.map(map(_)(f)))
        case Label(description, test) => Label(description, map(test)(f))
        case Pure(value)              => Pure(f(value))
        case Skip(test)               => Skip(map(test)(f))
        case success: Success[F, A]   => success.asInstanceOf[Success[F, B]]
        case Suspend(test)            => Suspend(test.map(map(_)(f)))
      }

    override def flatMap[A, B](fa: Test[F, A])(f: A => Test[F, B]): Test[F, B] =
      fa match {
        case error: Error[F, A]       => error.asInstanceOf[Error[F, B]]
        case failure: Failure[F, A]   => failure.asInstanceOf[Failure[F, B]]
        case Group(tests)             => Group(tests.map(flatMap(_)(f)))
        case Label(description, test) => Label(description, flatMap(test)(f))
        case Pure(value)              => f(value)
        case Skip(test)               => Skip(flatMap(test)(f))
        case success: Success[F, A]   => success.asInstanceOf[Success[F, B]]
        case Suspend(test)            => Suspend(test.map(flatMap(_)(f)))
      }

    override def tailRecM[A, B](a: A)(
        f: A => Test[F, Either[A, B]]): Test[F, B] = {
      def go(test: Test[F, Either[A, B]]): Test[F, B] = test match {
        case error: Error[F, _]       => error.asInstanceOf[Error[F, B]]
        case failure: Failure[F, _]   => failure.asInstanceOf[Failure[F, B]]
        case Group(tests)             => Group(tests.map(go))
        case Label(description, test) => Label(description, go(test))
        case Pure(Right(b))           => Pure(b)
        case Pure(Left(a))            => go(f(a))
        case Skip(test)               => Skip(go(test))
        case success: Success[F, _]   => success.asInstanceOf[Success[F, B]]
        case Suspend(test)            => Suspend(test.map(go))
      }

      go(f(a))
    }
  }

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
        case (Pure(x), Pure(y))           => x === y
        case (Skip(x), Skip(y))           => eqv(x, y)
        case (Success(), Success())       => true
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
      case Pure(value)              => show"Pure($value)"
      case Skip(test)               => s"Skip(${show(test)})"
      case Success()                => s"Success()"
      case Suspend(test)            => s"Defer($test)"
    }
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
}
