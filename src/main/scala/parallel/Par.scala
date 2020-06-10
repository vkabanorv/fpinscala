package parallel

import java.util.concurrent.{Callable, ExecutorService, Future, TimeUnit}

object Par {
    type Par[A] = ExecutorService => Future[A]

    def run[A](s: ExecutorService)(a: Par[A]): Future[A] = a(s)

    def unit[A](a: A): Par[A] = (es: ExecutorService) => UnitFuture(a)

    private case class UnitFuture[A](get: A) extends Future[A] {
        override def cancel(evenIfRunning: Boolean): Boolean = false
        override def isCancelled: Boolean = false
        override def isDone: Boolean = true

        override def get(timeout: Long, unit: TimeUnit) = get
    }

    def map2[A,B,C](a: Par[A], b: Par[B])(f: (A,B) => C): Par[C] =
        (es: ExecutorService) => {
            val af = a(es)
            val bf = b(es)
            UnitFuture(f(af.get, bf.get))
        }

    def map3[A,B,C,D](a: Par[A], b: Par[B], c: Par[C])(f: (A,B,C) => D): Par[D] = {
        val p1 = map2[A,B,C => D](a, b)( (x, y) => z => f(x,y,z))
        map2[C, C=>D, D](c, p1)( (x, f) => f(x))
    }

    def map4[A,B,C,D,E](a: Par[A], b: Par[B], c: Par[C], d: Par[D])(f: (A,B,C,D) => E): Par[E] = {
        val p1 = map3[A,B,C,D => E](a, b, c)( (a, b, c) => d => f(a, b, c, d))
        map2[D, D=>E, E](d, p1)( (x, f) => f(x))
    }

    def map5[A,B,C,D,E,F](a: Par[A], b: Par[B], c: Par[C], d: Par[D], e: Par[E])(f: (A,B,C,D,E) => F): Par[F] = {
        val p1 = map4[A,B,C,D,E => F](a, b, c, d)( (a, b, c, d) => e => f(a, b, c, d, e))
        map2[E, E=>F, F](e, p1)( (x, f) => f(x))
    }

    def fork[A](a: => Par[A]): Par[A] =
        (es: ExecutorService) => es.submit(new Callable[A] {
            def call = a(es).get
        })

    def lazyUnit[A](a: => A): Par[A] = fork(unit(a))

    def asyncF[A,B](f: A => B): A => Par[B] =
        a => lazyUnit(f(a))

    def map[A,B](pa: Par[A])(f: A => B): Par[B] =
        map2(pa, unit(()))((a,_) => f(a))

    def filter[A](pa: Par[List[A]])(f: A => Boolean): Par[List[A]] =
        map(pa)(a => a.filter(f))

    def foldLeft[A,B](pa: Par[List[A]])(z: B)(f: (B,A) => B): Par[B] =
        map(pa)(a => a.foldLeft(z)(f))

    def foldRight[A,B](pa: Par[List[A]])(z: B)(f: (A,B) => B): Par[B] =
        map(pa)(a => a.foldRight(z)(f))

    def sequence[A](ps: List[Par[A]]): Par[List[A]] =
        ps.foldRight[Par[List[A]]](unit(Nil: List[A]))( (a, as) => map2(a, as)( (a,as) => a :: as))

    def parMap[A,B](s: List[A])(f: A => B): Par[List[B]] = {
        val fbs: List[Par[B]] = s.map(asyncF(f))
        sequence(fbs)
    }

    def parFilter[A](s: List[A])(f: A => Boolean): Par[List[A]] = {
        val fas: List[Par[A]] = s.filter(f).map(asyncF(a => a))
        sequence(fas)
    }

    def parFoldLeft[A,B](s: List[A])(z: B)(f: (B,A) => B): Par[B] = {
        val ps = sequence(s.map(asyncF(a => a)))
        map(ps)( (a: List[A]) => a.foldLeft[B](z)(f) )
    }

    def parFoldRight[A,B](s: List[A])(z: B)(f: (A,B) => B): Par[B] = {
        val ps = sequence(s.map(asyncF(a => a)))
        map(ps)( (a: List[A]) => a.foldRight[B](z)(f) )
    }

    def parFlatMap[A,B](s: List[A])(f: A => List[B]): Par[List[B]] = {
        val ps = sequence(s.map(asyncF(a => a)))
        map(ps)( (a: List[A]) => a.flatMap(f))
    }

    /*def wordCounts(text: List[String])(regex: String)(es: ExecutorService): Map[String, Int] = {
        val words = parFlatMap(text)(t => t.split(regex).toList)
        map(words)(w => w.groupBy(a => a).mapValues(a => a.size))(es).get
    }
    */

    def chooser[A,B](pa: Par[A])(choices: A => Par[B]): Par[B] =
        es => {
            run(es)( choices(run(es)(pa).get()) )
        }

    def choice[A](cond: Par[Boolean])(t: Par[A], f: Par[A]): Par[A] =
        chooser(cond)(b => if(b) t else f)

    def choiceN[A](n: Par[Int])(choices: List[Par[A]]): Par[A] =
        chooser(n)(choices(_))

    def choiceMap[K,V](key: Par[K])(choices: Map[K,Par[V]]): Par[V] =
        chooser(key)(choices(_))

    def flatMap[A,B](a: Par[A])(b: A => Par[B]): Par[B] =
        es => {
            run(es)( b(run(es)(a).get()) )
        }

    def join[A](a: Par[Par[A]]): Par[A] =
        es => {
            run(es)( run(es)(a).get() )
        }

    def flatMap2[A,B](a: Par[A])(b: A => Par[B]): Par[B] =
        join(map(a)(b))

    def join2[A](a: Par[Par[A]]): Par[A] =
        flatMap[Par[A], A](a)(p => p)

    def equal[A](p: Par[A], p2: Par[A]): Par[Boolean] =
        Par.map2(p,p2)(_ == _)
}
