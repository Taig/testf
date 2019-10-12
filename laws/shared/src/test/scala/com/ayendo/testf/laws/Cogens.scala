package com.ayendo.testf.laws

import com.ayendo.testf.{Pure, Test}
import org.scalacheck.Cogen
import org.scalacheck.rng.Seed

object Cogens {
  implicit val cogenTest: Cogen[Test[Pure]] = Cogen({
    case (seed, Test.And(tests))         => Cogen.perturb(seed, tests)
    case (seed, test: Test.Eval[Pure])   => Cogen.perturb(seed, test.test)
    case (seed, Test.Error(message))     => Cogen.perturb(seed, message)
    case (seed, Test.Failure(throwable)) => Cogen.perturb(seed, throwable)
    case (seed, Test.Label(description, test)) =>
      Cogen.perturb(seed, (description, test))
    case (seed, Test.Message(description, test)) =>
      Cogen.perturb(seed, (description, test))
    case (seed, Test.Not(test))  => Cogen.perturb(seed, test)
    case (seed, Test.Or(tests))  => Cogen.perturb(seed, tests)
    case (seed, Test.Skip(test)) => Cogen.perturb(seed, test)
    case (seed, Test.Success)    => seed
  }: (Seed, Test[Pure]) => Seed)
}
