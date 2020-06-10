package testing

import java.util.concurrent.Executors

import state._

case class Gen[A](sample: State[RNG, A]) {
    def map[B](f: A => B): Gen[B] =
        Gen(sample.map(f))

    def flatMap2[B](f: A => Gen[B]): Gen[B] =
        Gen(sample.flatMap(a => f(a).sample))

    def map2[B,C](g: Gen[B])(f: (A,B) => C): Gen[C] =
        Gen(sample.map2(g.sample)(f))

    def flatMap[B](f: A => Gen[B]): Gen[B] =
        Gen(State((rng: RNG) => {
            val (g,r) = sample.map(f).run(rng)
            g.sample.run(r)
        }))

    def listOfN(size: Gen[Int]): Gen[List[A]] =
        size.flatMap(n => Gen.listOfN(n, this))

    def unsized: SGen[A] = SGen(size => this)

    def **[B](g: Gen[B]): Gen[(A,B)] =
        (this map2 g)((_,_))
}

case class SGen[A](g: Int => Gen[A]) {
    def flatMap[B](f: A => SGen[B]): SGen[B] =
        SGen(size => g(size).flatMap(
            a => f(a).g(size)
        ))

    def apply(n: Int): Gen[A] = g(n)

    def map[B](f: A => B): SGen[B] =
        SGen { g(_) map f}
}

object Gen {
    def choose(start: Int, stopExclusive: Int): Gen[Int] =
        Gen(State(RNG.nonNegativeInt).map(a => start + a % (stopExclusive - start)))

    def unit[A](a: => A): Gen[A] =
        Gen(State.unit(a))

    val boolean: Gen[Boolean] =
        Gen(State(RNG.boolean))

    val uniform: Gen[Double] =
        Gen(State(RNG.double))

    val double: Gen[Double] =
        Gen(State(RNG.double))

    def choose(i: Double, j: Double): Gen[Double] =
        Gen(State(RNG.double).map(d => i + d*(j-i)))

    def even(start: Int, stopExclusive: Int): Gen[Int] =
        choose(start, if (stopExclusive%2 == 0) stopExclusive - 1 else  stopExclusive).
            map(n => if (n%2 != 0) n+1 else n)

    def odd(start: Int, stopExclusive: Int): Gen[Int] =
        choose(start, if (stopExclusive%2 == 1) stopExclusive - 1 else stopExclusive).
            map(n => if (n%2 == 0) n+1 else n)

    def sameParity(from: Int, to: Int): Gen[(Int,Int)] = for {
        i <- choose(from,to)
        j <- if (i%2 == 0) even(from,to) else odd(from,to)
    } yield(i,j)

    def listOfN[A](n: Int, g: Gen[A]): Gen[List[A]] =
        g match {case Gen(s) =>
            Gen(State.sequence(List.fill(n)(s)))
        }

    def listOf[A](g: Gen[A]): SGen[List[A]] =
        SGen((size: Int) => listOfN(size, g))

    def listOf_1[A](g: Gen[A]): SGen[List[A]] =
        SGen((size: Int) => listOfN(size max 1, g))

    def union[A](g1: Gen[A], g2: Gen[A]): Gen[A] =
        boolean.flatMap(b => if(b) g1 else g2)

    def weighted[A](g1: (Gen[A],Double), g2: (Gen[A],Double)): Gen[A] = {
        val g1Threshold = g1._2.abs / (g1._2.abs + g2._2.abs)

        Gen(State(RNG.double).flatMap(d => if (d < g1Threshold) g1._1.sample else g2._1.sample))
    }

    val S = weighted(
        choose(1,4).map(Executors.newFixedThreadPool) -> .75,
        unit(Executors.newCachedThreadPool) -> .25
    )

    def stringN(n: Int): Gen[String] =
        listOfN(n, choose(0,127)).map(_.map(_.toChar).mkString)

    val string = SGen[String] = SGen(stringN)

    implicit def unsized[A](g: Gen[A]): SGen[A] = SGen(_ => g)
}

object ** {
    def unapply [A,B](p: (A,B)): Some[(A,B)] = Some(p)
}
