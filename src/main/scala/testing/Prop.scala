package testing

import parallel.Par.Par
import state.RNG
import testing.Prop._

case class Prop(run: (MaxSize,TestCases,RNG) => Result) {
    def &&(p: Prop): Prop = Prop {
        (max,n,rng) => run(max,n,rng) match {
            case Passed | Proved => p.run(max,n,rng)
            case x => x
        }
    }

    def ||(p: Prop): Prop = Prop {
        (max,n,rng) => run(max,n,rng) match {
            case Falsified(msg, _) => p.tag(msg).run(max,n,rng)
            case x => x
        }
    }

    def tag(msg: String) = Prop {
        (max,n,rng) => run(max,n,rng) match {
            case Falsified(e, c) => Falsified(msg + "\n" + e, c)
            case x => x
        }
    }
}

object Prop {
    type MaxSize = Int
    type TestCases = Int
    type FailedCase = String
    type SuccessCount = Int

    sealed trait Result {
        def isFalsified: Boolean
    }
    case object Passed extends Result {
        def isFalsified = false
    }
    case class Falsified(failure: FailedCase, successes: SuccessCount) extends Result {
        def isFalsified = true
    }
    case object Proved extends Result {
        def isFalsified: Boolean = false
    }

    def check(p: => Boolean): Prop = Prop { (_, _, _) =>
        if (p) Proved else Falsified("()", 0)
    }

    def checkPar(p: Par[Boolean]): Prop =
        forAllPar(Gen.unit(()))(_ => p)

    def run(p: Prop,
            maxSize: Int = 100,
            testCases: Int = 100,
            rng: RNG = RNG.SimpleRNG(System.currentTimeMillis())): Unit =
        p.run(maxSize, testCases, rng) match {
            case Falsified(msg, n) =>
                println(s"! Falsified after $n passed tests:\n $msg")
            case Passed =>
                println(s"+ OK, passed $testCases tests.")
            case Proved =>
                println(s"OK, proved")
        }

    def forAll[A](g: SGen[A])(f: A => Boolean): Prop =
        forAll(g(_))(f)

    def forAll[A](g: Int => Gen[A])(f: A => Boolean): Prop = Prop {
        (max,n,rng) =>
            val casesPerSize = (n + (max - 1)) / max
            val props: LazyList[Prop] =
                LazyList.from(0).take((n min max) + 1).map(i => forAll(g(i))(f))
            val prop: Prop =
                props.map(p => Prop { (max, _, rng) =>
                    p.run(max, casesPerSize, rng)
                }).toList.reduce(_ && _)
            prop.run(max, n, rng)
    }

    def forAll[A](as: Gen[A])(f: A => Boolean): Prop = Prop {
        (m,n,rng) => randomStream(as)(rng).zip(LazyList.from(0)).take(n).map({
            case (a, i) => try {
                if (f(a)) Passed else Falsified(a.toString, i)
            } catch { case e: Exception => Falsified(buildMsg(a, e), i)}
        }).find(_.isFalsified).getOrElse(Passed)
    }

    def forAllPar[A](g: Gen[A])(f: A => Par[Boolean]): Prop =
        forAll(Gen.S ** g) { case s ** a => f(a)(s).get}

    def apply(f: (TestCases,RNG) => Result): Prop =
        Prop { (_,n,rng) => f(n,rng)}

    def randomStream[A](g: Gen[A])(rng: RNG): LazyList[A] =
        LazyList.unfold(rng)(rng => Some(g.sample.run(rng)))

    def buildMsg[A](s: A, e: Exception): String =
        s"test case: $s\n" +
        s"generated an exception: ${e.getMessage}\n" +
        s"stack trace:\n ${e.getStackTrace.mkString("\n")}"
}