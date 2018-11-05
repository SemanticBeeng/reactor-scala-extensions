package reactor.core.scala.publisher

import java.time.{Duration => JDuration}
import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import org.mockito.Mockito.{spy, verify}
import org.mockito.{ArgumentMatchers, Mockito}
import org.reactivestreams.Subscription
import org.scalatest.mockito.MockitoSugar
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{AsyncFreeSpec, FreeSpec, Matchers}
import reactor.core.Disposable
import reactor.core.publisher.{BaseSubscriber, Signal, SynchronousSink, Flux => JFlux, Mono => JMono}
import reactor.core.scala.Scannable
import reactor.core.scala.publisher.Mono.just
import reactor.core.scheduler.{Scheduler, Schedulers}
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.{existentials, postfixOps}
import scala.math.ScalaNumber
import scala.util.Random

/**
  * Created by winarto on 12/26/16.
  */
class MonoTest extends FreeSpec with Matchers with TableDrivenPropertyChecks with MockitoSugar with TestSupport {

  private val randomValue = Random.nextLong()
  "Mono" - {
    ".create should create a Mono" in {
      val mono = createMono

      StepVerifier.create(mono)
        .expectNext(randomValue)
        .expectComplete()
        .verify()
    }

    ".defer should create a Mono with deferred Mono" in {
      val mono = Mono.defer(() => createMono)

      StepVerifier.create(mono)
        .expectNext(randomValue)
        .expectComplete()
        .verify()
    }

    ".delay should create a Mono with the first element delayed according to the provided" - {
      "duration" in {
        StepVerifier.withVirtualTime(() => Mono.delay(5 days))
          .thenAwait(JDuration.ofDays(5))
          .expectNextCount(1)
          .expectComplete()
          .verify()
      }

      "duration in millis with given TimeScheduler" in {
        val vts = VirtualTimeScheduler.getOrSet()
        StepVerifier.create(Mono.delay(50 seconds, vts))
          .`then`(() => vts.advanceTimeBy(50 seconds))
          .expectNextCount(1)
          .expectComplete()
          .verify()

      }
    }

    ".empty " - {
      "without source should create an empty Mono" in {
        val mono = Mono.empty
        verifyEmptyMono(mono)
      }

      def verifyEmptyMono[T](mono: Mono[T]) = {
        StepVerifier.create(mono)
          .expectComplete()
          .verify()
      }
    }

    ".error should create Mono that emit error" in {
      val mono = Mono.error(new RuntimeException("runtime error"))
      StepVerifier.create(mono)
        .expectError(classOf[RuntimeException])
        .verify()
    }

    ".first" - {
      "with varargs should create mono that emit the first item" in {
        StepVerifier.withVirtualTime(() => Mono.first(Mono.just(1).delaySubscription(3 seconds), Mono.just(2).delaySubscription(2 seconds)))
          .thenAwait(3 seconds)
          .expectNext(2)
          .verifyComplete()
      }
      "with iterable should create mono that emit the first item" in {
        StepVerifier.withVirtualTime(() => Mono.first(Iterable(Mono.just(1).delaySubscription(3 seconds), Mono.just(2).delaySubscription(2 seconds))))
          .thenAwait(3 seconds)
          .expectNext(2)
          .verifyComplete()
      }
    }

    ".from" - {
      "a publisher should ensure that the publisher will emit 0 or 1 item." in {
        val publisher: JFlux[Int] = JFlux.just(1, 2, 3, 4, 5)

        val mono = Mono.from(publisher)

        StepVerifier.create(mono)
          .expectNext(1)
          .expectComplete()
          .verify()
      }

      "a callable should ensure that Mono will return a value from the Callable" in {
        val callable = new Callable[Long] {
          override def call(): Long = randomValue
        }
        val mono = Mono.fromCallable(callable)
        StepVerifier.create(mono)
          .expectNext(randomValue)
          .expectComplete()
          .verify()
      }

      "source direct should return mono of the source" in {
        val mono = Mono.fromDirect(Flux.just(1, 2, 3))
        StepVerifier.create(mono)
          .expectNext(1, 2, 3)
          .verifyComplete()
      }

      "a future should result Mono that will return the value from the future object" in {
        import scala.concurrent.ExecutionContext.Implicits.global
        val future = Future[Long] {
          randomValue
        }

        val mono = Mono.fromFuture(future)
        StepVerifier.create(mono)
          .expectNext(randomValue)
          .expectComplete()
          .verify()
      }

      "a Runnable should run the unit within it" in {
        val atomicLong = new AtomicLong()
        val runnable = new Runnable {
          override def run(): Unit = atomicLong.set(randomValue)
        }
        val mono = Mono.fromRunnable(runnable)
        StepVerifier.create(mono)
          .expectComplete()
          .verify()
        atomicLong.get() shouldBe randomValue
      }

      "a Supplier should result Mono that will return the result from supplier" in {
        val mono = Mono.fromSupplier(() => randomValue)
        StepVerifier.create(mono)
          .expectNext(randomValue)
          .expectComplete()
          .verify()
      }
    }

    ".ignoreElements should ignore all elements from a publisher and just react on completion signal" in {
      val mono = Mono.ignoreElements(createMono)
      StepVerifier.create(mono)
        .expectComplete()
        .verify()
    }

    ".just should emit the specified item" in {
      val mono = just(randomValue)
      StepVerifier.create(mono)
        .expectNext(randomValue)
        .verifyComplete()
    }

    ".justOrEmpty" - {
      "with Option should" - {
        "emit the specified item if the option is not empty" in {
          val mono = Mono.justOrEmpty(Option(randomValue))
          StepVerifier.create(mono)
            .expectNext(randomValue)
            .verifyComplete()
        }
        "just react on completion signal if the option is empty" in {
          val mono = Mono.justOrEmpty(Option.empty)
          StepVerifier.create(mono)
            .expectComplete()
            .verify()
        }
      }
      "with data should" - {
        "emit the specified item if it is not null" in {
          val mono = Mono.justOrEmpty(randomValue)
          StepVerifier.create(mono)
            .expectNext(randomValue)
            .verifyComplete()
        }
      }
    }

    ".name should name the sequence" in {
      val name = "mono integer"
      val mono = Mono.just(randomValue).name(name)
      val scannable: Scannable = Scannable.from(Option(mono))
      scannable.name shouldBe name
    }

    ".never will never signal any data, error or completion signal" in {
      val mono = Mono.never
      StepVerifier.create(mono)
        .expectNoEvent(1 second)
    }


    ".sequenceEqual should" - {
      "emit Boolean.TRUE when both publisher emit the same value" in {
        val emittedValue = new AtomicBoolean(false)
        val isSubscribed = new AtomicBoolean(false)

        val mono = Mono.sequenceEqual(just(1), just(1))
        mono.subscribe(new BaseSubscriber[Boolean] {
          override def hookOnSubscribe(subscription: Subscription): Unit = {
            subscription.request(1)
            isSubscribed.compareAndSet(false, true)
          }

          override def hookOnNext(value: Boolean): Unit = emittedValue.compareAndSet(false, true)
        })
        isSubscribed shouldBe 'get
        emittedValue shouldBe 'get
      }
      "emit true when both publisher emit the same value according to the isEqual function" in {
        val mono = Mono.sequenceEqual[Int](just(10), just(100), (t1: Int, t2: Int) => t1 % 10 == t2 % 10)
        StepVerifier.create(mono)
          .expectNext(true)
          .verifyComplete()
      }
      "emit true when both publisher emit the same value according to the isEqual function with bufferSize" in {
        val mono = Mono.sequenceEqual[Int](just(10), just(100), (t1: Int, t2: Int) => t1 % 10 == t2 % 10, 2)
        StepVerifier.create(mono)
          .expectNext(true)
          .verifyComplete()

      }
    }

    ".when" - {
      "with iterable" - {
        "of publisher of unit should return when all of the sources has fulfilled" in {
          val completed = new ConcurrentHashMap[String, Boolean]()
          val mono = Mono.when(Iterable(
            just[Unit]({
              completed.put("first", true)
            }),
            just[Unit]({
              completed.put("second", true)
            })
          ))
          StepVerifier.create(mono)
            .expectComplete()
          completed should contain key "first"
          completed should contain key "second"
        }
      }

      "with varargs of publisher should return when all of the resources has fulfilled" in {
        val completed = new ConcurrentHashMap[String, Boolean]()
        val sources = Seq(just[Unit]({
          completed.put("first", true)
        }),
          just[Unit]({
            completed.put("second", true)
          })
        )
        val mono = Mono.when(sources.toArray: _*)
        StepVerifier.create(mono)
          .expectComplete()
        completed should contain key "first"
        completed should contain key "second"
      }
    }

    ".zipDelayError" - {
      "with p1 and p2 should merge when both Monos are fulfilled" in {
        StepVerifier.create(Mono.zipDelayError(just(1), just("one")))
          .expectNext((1, "one"))
          .verifyComplete()
      }

      "with p1, p2 and p3 should merge when all Monos are fulfilled" in {
        StepVerifier.create(Mono.zipDelayError(just(1), just("one"), just(1L)))
          .expectNext((1, "one", 1L))
          .verifyComplete()
      }

      "with p1, p2, p3 and p4 should merge when all Monos are fulfilled" in {
        StepVerifier.create(Mono.zipDelayError(just(1), just(2), just(3), just(4)))
          .expectNext((1, 2, 3, 4))
          .verifyComplete()
      }

      "with p1, p2, p3, p4 and p5 should merge when all Monos are fulfilled" in {
        StepVerifier.create(Mono.zipDelayError(just(1), just(2), just(3), just(4), just(5)))
          .expectNext((1, 2, 3, 4, 5))
          .verifyComplete()
      }

      "with p1, p2, p3, p4, p5 and p6 should merge when all Monos are fulfilled" in {
        StepVerifier.create(Mono.zipDelayError(just(1), just(2), just(3), just(4), just(5), just(6)))
          .expectNext((1, 2, 3, 4, 5, 6))
          .verifyComplete()
      }

      "with iterable" - {
        "of publisher of unit should return when all of the sources has fulfilled" in {
          val completed = new ConcurrentHashMap[String, Boolean]()
          val mono = Mono.whenDelayError(Iterable(
            just[Unit]({
              completed.put("first", true)
            }),
            just[Unit]({
              completed.put("second", true)
            })
          ))
          StepVerifier.create(mono)
            .expectComplete()
          completed should contain key "first"
          completed should contain key "second"
        }

        "of Mono and combinator function should emit the value after combined by combinator function" in {
          StepVerifier.create(Mono.zipDelayError(Iterable(Mono.just(1), Mono.just("one")), (values: Array[AnyRef]) => s"${values(0).toString}-${values(1).toString}"))
            .expectNext("1-one")
            .verifyComplete()
        }
      }

      "with varargs of Publisher[Unit] should be fulfilled when all the underlying sources are fulfilled" in {
        val completed = new ConcurrentHashMap[String, Boolean]()
        val mono = Mono.whenDelayError(
          Seq(
            just[Unit](completed.put("first", true)),
            just[Unit](completed.put("second", true))
          ).toArray: _*
        )
        StepVerifier.create(mono)
          .expectComplete()

        completed should contain key "first"
        completed should contain key "second"
      }

      "with function combinator and varargs of mono should return when all of the monos has fulfilled" in {
        val combinator: (Array[Any] => String) = { values =>
          values.map(_.toString).foldLeft("") { (acc, value) => if (acc.isEmpty) s"$value" else s"$acc-$value" }
        }

        StepVerifier.create(Mono.whenDelayError(combinator, just[Any](1), just[Any](2)))
          .expectNext("1-2")
          .expectComplete()
          .verify()
      }
    }

    ".zip" - {
      val combinator: (Array[AnyRef] => String) = { datas => datas.map(_.toString).foldLeft("") { (acc, v) => if (acc.isEmpty) v else s"$acc-$v" } }
      "with combinator function and varargs of mono should fullfill when all Monos are fulfilled" in {
        val mono = Mono.zip(combinator, just(1), just(2))
        StepVerifier.create(mono)
          .expectNext("1-2")
          .verifyComplete()
      }
      "with combinator function and Iterable of mono should fulfill when all Monos are fulfilled" in {
        val mono = Mono.zip(Iterable(just(1), just(2)), combinator)
        StepVerifier.create(mono)
          .expectNext("1-2")
          .verifyComplete()
      }
    }

    ".as should transform the Mono to whatever the transformer function is provided" in {
      val mono = just(randomValue)

      val flux = mono.as(m => Flux.from(m))
      StepVerifier.create(flux)
        .expectNext(randomValue)
        .verifyComplete()
    }

    ".and" - {
      "should combine this mono and the other" in {
        val mono: Mono[Unit] = just(1) and just(2)
        StepVerifier.create(mono)
          //          .expectNext((1, 2))
          .verifyComplete()
      }
    }

    ".block" - {
      "should block the mono to get the value" in {
        Mono.just(randomValue).block() shouldBe randomValue
      }
      "with duration should block the mono up to the duration" in {
        Mono.just(randomValue).block(10 seconds) shouldBe randomValue
      }
    }

    ".blockOption" - {
      "without duration" - {
        "should block the mono to get value" in {
          Mono.just(randomValue).blockOption() shouldBe Some(randomValue)
        }
        "should retun None if mono is empty" in {
          Mono.empty.blockOption() shouldBe None
        }
      }
      "with duration" - {
        "should block the mono up to the duration" in {
          Mono.just(randomValue).blockOption(10 seconds) shouldBe Some(randomValue)
        }
        "shouldBlock the mono up to the duration and return None" in {
          StepVerifier.withVirtualTime(() => Mono.just(Mono.empty.blockOption(10 seconds)))
            .thenAwait(10 seconds)
            .expectNext(None)
            .verifyComplete()
        }
      }
    }

    ".cast should cast the underlying value" in {
      val number = Mono.just(BigDecimal("123")).cast(classOf[ScalaNumber]).block()
      number shouldBe a[ScalaNumber]
    }

    ".cache" - {
      "should cache the value" in {
        val queue = new ArrayBlockingQueue[Int](1)
        queue.put(1)
        val mono = Mono.create[Int](sink => {
          sink.success(queue.poll())
        }).cache()
        StepVerifier.create(mono)
          .expectNext(1)
          .verifyComplete()
        StepVerifier.create(mono)
          .expectNext(1)
          .verifyComplete()
      }
      "with ttl cache the value up to specific time" in {
        import reactor.test.scheduler.VirtualTimeScheduler
        val timeScheduler = VirtualTimeScheduler.getOrSet
        val queue = new ArrayBlockingQueue[Int](1)
        queue.put(1)
        val mono = Mono.create[Int](sink => {
          sink.success(queue.poll())
        }).cache(1 minute)
        StepVerifier.create(mono)
          .expectNext(1)
          .verifyComplete()
        timeScheduler.advanceTimeBy(59 second)
        StepVerifier.create(mono)
          .expectNext(1)
          .verifyComplete()
        timeScheduler.advanceTimeBy(2 minute)
        StepVerifier.create(mono)
          .verifyComplete()
      }
    }


    ".cancelOn should cancel the subscriber on a particular scheduler" in {
      val jMono = spy(JMono.just(1))
      Mono(jMono).cancelOn(Schedulers.immediate())
      Mockito.verify(jMono).cancelOn(ArgumentMatchers.any[Scheduler]())
    }

    ".compose should defer creating the target mono type" in {
      val mono = Mono.just(1)
      val mono1: Mono[String] = mono.compose[String](m => Flux.from(m.map(_.toString)))

      StepVerifier.create(mono1)
        .expectNext("1")
        .verifyComplete()
    }

    ".concatWith should concatenate mono with another source" in {
      val mono = Mono.just(1)
      StepVerifier.create(mono.concatWith(Mono.just(2)))
        .expectNext(1)
        .expectNext(2)
        .verifyComplete()
    }

    ".defaultIfEmpty should use the provided default value if the mono is empty" in {
      val mono = Mono.empty[Int]
      StepVerifier.create(mono.defaultIfEmpty(-1))
        .expectNext(-1)
        .verifyComplete()
    }

    ".delayElement" - {
      "should delay the element" in {
        StepVerifier.withVirtualTime(() => Mono.just(randomValue).delayElement(5 seconds))
          .thenAwait(5 seconds)
          .expectNext(randomValue)
          .verifyComplete()
      }
      "with timer should delay using timer" in {
        StepVerifier.withVirtualTime(() => Mono.just(randomValue).delayElement(5 seconds, Schedulers.parallel()))
          .thenAwait(5 seconds)
          .expectNext(randomValue)
          .verifyComplete()
      }
    }

    ".delayUntil should delay until the other provider terminate" in {
      StepVerifier.withVirtualTime(() => Mono.just(randomValue).delayUntil(_ => Flux.just(1, 2).delayElements(2 seconds)))
        .thenAwait(4 seconds)
        .expectNext(randomValue)
        .verifyComplete()
    }

    ".delaySubscription" - {
      "with delay duration should delay subscription as long as the provided duration" in {
        StepVerifier.withVirtualTime(() => Mono.just(1).delaySubscription(1 hour))
          .thenAwait(1 hour)
          .expectNext(1)
          .verifyComplete()
      }
      "with delay duration and scheduler should delay subscription as long as the provided duration" in {
        StepVerifier.withVirtualTime(() => Mono.just(1).delaySubscription(1 hour, Schedulers.single()))
          .thenAwait(1 hour)
          .expectNext(1)
          .verifyComplete()
      }
      "with another publisher should delay the current subscription until the other publisher completes" in {
        StepVerifier.withVirtualTime(() => Mono.just(1).delaySubscription(Mono.just("one").delaySubscription(1 hour)))
          .thenAwait(JDuration.ofHours(1))
          .expectNext(1)
          .verifyComplete()

      }
    }

    ".dematerialize should dematerialize the underlying mono" in {
      val mono = Mono.just(Signal.next(randomValue))
      StepVerifier.create(mono.dematerialize())
        .expectNext(randomValue)
        .verifyComplete()
    }

    ".doAfterSuccessOrError should call the callback function after the mono is terminated" in {
      val atomicBoolean = new AtomicBoolean(false)
      val mono = Mono.just(randomValue)
        .doAfterSuccessOrError { (_: Long, _: Throwable) =>
          atomicBoolean.compareAndSet(false, true) shouldBe true
          ()
        }
      StepVerifier.create(mono)
        .expectNext(randomValue)
        .verifyComplete()
      atomicBoolean shouldBe 'get
    }

    ".doAfterTerminate should call the callback function after the mono is terminated" in {
      val atomicBoolean = new AtomicBoolean(false)
      StepVerifier.create(Mono.just(randomValue).doAfterTerminate(() => atomicBoolean.compareAndSet(false, true)))
        .expectNext(randomValue)
        .verifyComplete()
      atomicBoolean shouldBe 'get
    }


    ".doFinally should call the callback" in {
      val atomicBoolean = new AtomicBoolean(false)
      val mono = Mono.just(randomValue)
        .doFinally(_ => atomicBoolean.compareAndSet(false, true) shouldBe true)
      StepVerifier.create(mono)
        .expectNext(randomValue)
        .verifyComplete()
      atomicBoolean shouldBe 'get
    }

    ".doOnCancel should call the callback function when the subscription is cancelled" in {
      val atomicBoolean = new AtomicBoolean(false)
      val mono = Mono.delay(1 minute)
        .doOnCancel(() => {
          atomicBoolean.compareAndSet(false, true) shouldBe true
        })

      val subscriptionReference = new AtomicReference[Subscription]()
      mono.subscribe(new BaseSubscriber[Long] {
        override def hookOnSubscribe(subscription: Subscription): Unit = {
          subscriptionReference.set(subscription)
          subscription.request(1)
        }

        override def hookOnNext(value: Long): Unit = ()
      })
      subscriptionReference.get().cancel()
      atomicBoolean shouldBe 'get
    }

    ".doOnNext should call the callback function when the mono emit data successfully" in {
      val atomicLong = new AtomicLong()
      val mono = Mono.just(randomValue)
        .doOnNext(t => atomicLong.compareAndSet(0, t))
      StepVerifier.create(mono)
        .expectNext(randomValue)
        .verifyComplete()
      atomicLong.get() shouldBe randomValue
    }

    ".doOnSuccess should call the callback function when the mono completes successfully" in {
      val atomicBoolean = new AtomicBoolean(false)
      val mono = Mono.empty[Int]
        .doOnSuccess(_ => atomicBoolean.compareAndSet(false, true) shouldBe true)
      StepVerifier.create(mono)
        .verifyComplete()
      atomicBoolean shouldBe 'get
    }

    ".doOnError" - {
      "with callback function should call the callback function when the mono encounter error" in {
        val atomicBoolean = new AtomicBoolean(false)
        val mono = Mono.error(new RuntimeException())
          .doOnError(_ => atomicBoolean.compareAndSet(false, true) shouldBe true)
        StepVerifier.create(mono)
          .expectError(classOf[RuntimeException])
      }
      "with exception type and callback function should call the callback function when the mono encounter exception with the provided type" in {
        val atomicBoolean = new AtomicBoolean(false)
        val mono = Mono.error(new RuntimeException())
          .doOnError(classOf[RuntimeException]: Class[RuntimeException],
            ((_: RuntimeException) => atomicBoolean.compareAndSet(false, true) shouldBe true): SConsumer[RuntimeException])
        StepVerifier.create(mono)
          .expectError(classOf[RuntimeException])
          .verify()
        atomicBoolean shouldBe 'get
      }
      "with predicate and callback fnction should call the callback function when the predicate returns true" in {
        val atomicBoolean = new AtomicBoolean(false)
        val mono: Mono[Int] = Mono.error[Int](new RuntimeException("Whatever"))
          .doOnError((_: Throwable) => true,
            ((_: Throwable) => atomicBoolean.compareAndSet(false, true) shouldBe true): SConsumer[Throwable])
        StepVerifier.create(mono)
          .expectError(classOf[RuntimeException])

      }
    }

    ".doOnRequest should call the callback function when subscriber request data" in {
      val atomicLong = new AtomicLong(0)
      val mono = Mono.just(randomValue)
        .doOnRequest{
          l => {
            atomicLong.compareAndSet(0, l)
          }
        }
      mono.subscribe(new BaseSubscriber[Long] {
        override def hookOnSubscribe(subscription: Subscription): Unit = {
          subscription.request(1)
          ()
        }

        override def hookOnNext(value: Long): Unit = ()
      })
      atomicLong.get() shouldBe 1
    }

    ".doOnSubscribe should call the callback function when the mono is subscribed" in {
      val atomicBoolean = new AtomicBoolean(false)
      val mono = Mono.just(randomValue)
        .doOnSubscribe(_ => atomicBoolean.compareAndSet(false, true))
      StepVerifier.create(mono)
        .expectNextCount(1)
        .verifyComplete()
      atomicBoolean shouldBe 'get
    }

    ".doOnTerminate should do something on terminate" in {
      val atomicLong = new AtomicLong()
      val mono: Mono[Long] = createMono.doOnTerminate { () => atomicLong.set(randomValue) }
      StepVerifier.create(mono)
        .expectNext(randomValue)
        .expectComplete()
        .verify()
      atomicLong.get() shouldBe randomValue
    }

    ".elapsed" - {
      "should provide the time elapse when this mono emit value" in {
        StepVerifier.withVirtualTime(() => Mono.just(randomValue).delaySubscription(1 second).elapsed(), 1)
          .thenAwait(1 second)
          .expectNextMatches((t: (Long, Long)) => t match {
            case (time, data) => time >= 1000 && data == randomValue
          })
          .verifyComplete()
      }
      "with TimedScheduler should provide the time elapsed using the provided scheduler when this mono emit value" in {
        val virtualTimeScheduler = VirtualTimeScheduler.getOrSet()
        StepVerifier.create(Mono.just(randomValue)
          .delaySubscription(1 second, virtualTimeScheduler)
          .elapsed(virtualTimeScheduler), 1)
          .`then`(() => virtualTimeScheduler.advanceTimeBy(1 second))
          .expectNextMatches((t: (Long, Long)) => t match {
            case (time, data) => time >= 1000 && data == randomValue
          })
          .verifyComplete()
      }
    }

    ".expandDeep" - {
      "should expand the mono" in {
        val flux = Mono.just("a").expandDeep(s => Mono.just(s"$s$s")).take(3)
        StepVerifier.create(flux)
          .expectNext("a", "aa", "aaaa")
          .verifyComplete()
      }
      "with capacity hint should expand the mono" in {
        val flux = Mono.just("a").expandDeep(s => Mono.just(s"$s$s"), 10).take(3)
        StepVerifier.create(flux)
          .expectNext("a", "aa", "aaaa")
          .verifyComplete()
      }
    }

    ".expand" - {
      "should expand the mono" in {
        val flux = Mono.just("a").expand(s => Mono.just(s"$s$s")).take(3)
        StepVerifier.create(flux)
          .expectNext("a", "aa", "aaaa")
          .verifyComplete()
      }
      "with capacity hint should expand the mono" in {
        val flux = Mono.just("a").expand(s => Mono.just(s"$s$s"), 10).take(3)
        StepVerifier.create(flux)
          .expectNext("a", "aa", "aaaa")
          .verifyComplete()
      }
    }

    ".filter should filter the value of mono where it pass the provided predicate" in {
      val mono = Mono.just(10)
        .filter(i => i < 10)
      StepVerifier.create(mono)
        .verifyComplete()
    }

    ".filterWhen should replay the value of mono if the first item emitted by the test is true" in {
      val mono = Mono.just(10).filterWhen((i: Int) => Mono.just(i % 2 == 0))
      StepVerifier.create(mono)
        .expectNext(10)
        .verifyComplete()
    }

    ".flatMap should flatmap the provided mono" in {
      val mono = Mono.just(randomValue).flatMap(l => Mono.just(l.toString))
      StepVerifier.create(mono)
        .expectNext(randomValue.toString)
        .verifyComplete()
    }

    ".flatMapMany" - {
      "with a single mapper should flatmap the value mapped by the provided mapper" in {
        val flux = Mono.just(1).flatMapMany(i => Flux.just(i, i * 2))
        StepVerifier.create(flux)
          .expectNext(1, 2)
          .verifyComplete()
      }
      "with mapperOnNext, mapperOnError and mapperOnComplete should mapped each individual event into values emitted by flux" in {
        val flux = Mono.just(1)
          .flatMapMany(
            _ => Mono.just("one"),
            _ => Mono.just("error"),
            () => Mono.just("complete")
          )
        StepVerifier.create(flux)
          .expectNext("one", "complete")
          .verifyComplete()
      }
    }

    ".flatMapIterable should flatmap the value mapped by the provided mapper" in {
      val flux = Mono.just("one").flatMapIterable(str => str.toCharArray)
      StepVerifier.create(flux)
        .expectNext('o', 'n', 'e')
        .verifyComplete()
    }

    ".flux should convert this mono into a flux" in {
      val flux = Mono.just(randomValue).flux()
      StepVerifier.create(flux)
        .expectNext(randomValue)
        .verifyComplete()
    }

    ".hasElement should convert to another Mono that emit" - {
      "true if it has element" in {
        val mono = Mono.just(1).hasElement
        StepVerifier.create(mono)
          .expectNext(true)
          .verifyComplete()
      }
      "false if it is empty" in {
        val mono = Mono.empty.hasElement
        StepVerifier.create(mono)
          .expectNext(false)
          .verifyComplete()
      }
    }

    ".handle should handle onNext, onError and onComplete" in {
      StepVerifier.create(Mono.just(randomValue)
        .handle((_: Long, s: SynchronousSink[String]) => {
          s.next("One")
          s.complete()
        }))
        .expectNext("One")
        .verifyComplete()
    }

    ".ignoreElement should only emit termination event" in {
      val mono = Mono.just(randomValue).ignoreElement
      StepVerifier.create(mono)
        .verifyComplete()
    }

    ".map should map the type of Mono from T to R" in {
      val mono = createMono.map(_.toString)

      StepVerifier.create(mono)
        .expectNext(randomValue.toString)
        .expectComplete()
        .verify()
    }

    ".mapError" - {
      class MyCustomException(val message: String) extends Exception(message)
      "with mapper should map the error to another error" in {
        val mono: Mono[Int] = Mono.error[Int](new RuntimeException("runtimeException"))
          .onErrorMap(t => new MyCustomException(t.getMessage))
        StepVerifier.create(mono)
          .expectErrorMatches((t: Throwable) => {
            t.getMessage shouldBe "runtimeException"
            t should not be a[RuntimeException]
            t shouldBe a[MyCustomException]
            true
          })
          .verify()
      }
      "with an error type and mapper should" - {
        "map the error to another type if the exception is according to the provided type" in {
          val mono: Mono[Int] = Mono.error[Int](new RuntimeException("runtimeException"))
            .onErrorMap(classOf[RuntimeException], (t: RuntimeException) => new MyCustomException(t.getMessage))
          StepVerifier.create(mono)
            .expectErrorMatches((t: Throwable) => {
              t.getMessage shouldBe "runtimeException"
              t should not be a[RuntimeException]
              t shouldBe a[MyCustomException]
              true
            })
            .verify()
        }
        "not map the error if the exception is not the type of provided exception class" in {
          val mono: Mono[Int] = Mono.error[Int](new Exception("runtimeException"))
            .onErrorMap(classOf[RuntimeException], (t: RuntimeException) => new MyCustomException(t.getMessage))
          StepVerifier.create(mono)
            .expectErrorMatches((t: Throwable) => {
              t.getMessage shouldBe "runtimeException"
              t should not be a[MyCustomException]
              t shouldBe a[Exception]
              true
            })
            .verify()
        }
      }
      "with a predicate and mapper should" - {
        "map the error to another type if the predicate returns true" in {
          val mono: Mono[Int] = Mono.error[Int](new RuntimeException("should map"))
            .onErrorMap(t => t.getMessage == "should map", t => new MyCustomException(t.getMessage))
          StepVerifier.create(mono)
            .expectError(classOf[MyCustomException])
            .verify()
        }
        "not map the error to another type if the predicate returns false" in {
          val mono: Mono[Int] = Mono.error[Int](new RuntimeException("should not map"))
            .onErrorMap(t => t.getMessage == "should map", t => new MyCustomException(t.getMessage))
          StepVerifier.create(mono)
            .expectError(classOf[RuntimeException])
            .verify()
        }
      }
    }

    ".materialize should convert the mono into a mono that emit its signal" in {
      val mono = Mono.just(randomValue).materialize()
      StepVerifier.create(mono)
        .expectNext(Signal.next(randomValue))
        .verifyComplete()
    }

    ".mergeWith should convert this mono to flux with value emitted from this mono followed by the other" in {
      val flux = Mono.just(1).mergeWith(Mono.just(2))
      StepVerifier.create(flux)
        .expectNext(1, 2)
        .verifyComplete()
    }

    ".or should return Mono that emit the value between the two Monos that is emited first" in {
      val mono = Mono.delay(5 seconds).or(Mono.just(2))
      StepVerifier.create(mono)
        .expectNext(2)
        .verifyComplete()
    }

    ".ofType should" - {
      "convert the Mono value type to the provided type if it can be casted" in {
        val mono = Mono.just(BigDecimal("1")).ofType(classOf[ScalaNumber])
        StepVerifier.create(mono)
          .expectNextCount(1)
          .verifyComplete()
      }
      "ignore the Mono value if it can't be casted" in {
        val mono = Mono.just(1).ofType(classOf[String])
        StepVerifier.create(mono)
          .expectComplete()
          .verify()
      }
    }

    ".onErrorRecover" - {
      "should recover with a Mono of element that has been recovered" in {
        val convoy = Mono.error(new RuntimeException("oops"))
          .onErrorRecover {case _ => Truck(5)}
        StepVerifier.create(convoy)
          .expectNext(Truck(5))
          .verifyComplete()
      }
    }

    ".onErrorRecoverWith" - {
      "should recover with a Flux of element that is provided for recovery" in {
        val convoy = Mono.error(new RuntimeException("oops"))
          .onErrorRecoverWith {case _ => Mono.just(Truck(5))}
        StepVerifier.create(convoy)
          .expectNext(Truck(5))
          .verifyComplete()
      }
    }

    ".onErrorResume" - {
      "will fallback to the provided value when error happens" in {
        val mono = Mono.error(new RuntimeException()).onErrorResume(_ => Mono.just(-1))
        StepVerifier.create(mono)
          .expectNext(-1)
          .verifyComplete()
      }
      "with class type and fallback function will fallback to the provided value when the exception is of provided type" in {
        val mono = Mono.error(new RuntimeException()).onErrorResume(classOf[RuntimeException], (_: Exception) => Mono.just(-1))
        StepVerifier.create(mono)
          .expectNext(-1)
          .verifyComplete()
      }
      "with predicate and fallback function will fallback to the provided value when the predicate returns true" in {
        val mono = Mono.error(new RuntimeException("fallback")).onErrorResume(t => t.getMessage == "fallback", (_: Throwable) => Mono.just(-1))
        StepVerifier.create(mono)
          .expectNext(-1)
          .verifyComplete()
      }
    }

    ".switchIfEmpty with alternative will emit the value from alternative Mono when this mono is empty" in {
      val mono = Mono.empty.switchIfEmpty(Mono.just(-1))
      StepVerifier.create(mono)
        .expectNext(-1)
        .verifyComplete()
    }

    ".onErrorReturn" - {
      "with fallback will emit to the fallback value when error occurs" in {
        val mono = Mono.error(new RuntimeException).onErrorReturn(-1)
        StepVerifier.create(mono)
          .expectNext(-1)
          .verifyComplete()
      }
      class MyCustomException(message: String) extends Exception(message)
      "with exception type and fallback value will emit the fallback value when exception of provided type occurs" in {
        val mono = Mono.error(new MyCustomException("whatever")).onErrorReturn(classOf[MyCustomException], -1)
        StepVerifier.create(mono)
          .expectNext(-1)
          .verifyComplete()
      }
      "with predicate of exception and fallback value will emit the fallback value when predicate exception return true" in {
        val mono = Mono.error(new MyCustomException("should fallback")).onErrorReturn(t => t.getMessage == "should fallback", -1)
        StepVerifier.create(mono)
          .expectNext(-1)
          .verifyComplete()
      }
    }

    ".publish should share share and may transform it and consume it as many times as necessary without causing" +
      "multiple subscription" in {
      val mono = Mono.just(randomValue).publish[String](ml => ml.map(l => l.toString))

      val counter = new AtomicLong()

      val subscriber = new BaseSubscriber[String] {
        override def hookOnSubscribe(subscription: Subscription): Unit = {
          subscription.request(1)
          counter.incrementAndGet()
        }

        override def hookOnNext(value: String): Unit = ()
      }
      mono.subscribe(subscriber)
      mono.subscribe(subscriber)
      counter.get() shouldBe 1
    }

    ".repeat" - {
      "should return flux that repeat the value from this mono" in {
        val flux = Mono.just(randomValue).repeat().take(3)
        StepVerifier.create(flux)
          .expectNext(randomValue, randomValue, randomValue)
          .verifyComplete()
      }
      "with boolean predicate should repeat the value from this mono as long as the predicate returns true" in {
        val counter = new AtomicLong()
        val flux = Mono.just(randomValue)
          .repeat(() => counter.get() < 3)
        val buffer = new LinkedBlockingQueue[Long]()
        val latch = new CountDownLatch(1)
        flux.subscribe(new BaseSubscriber[Long] {
          override def hookOnSubscribe(subscription: Subscription): Unit = subscription.request(Long.MaxValue)

          override def hookOnNext(value: Long): Unit = {
            counter.incrementAndGet()
            buffer.put(value)
          }

          override def hookOnComplete(): Unit = latch.countDown()
        })
        if (latch.await(1, TimeUnit.SECONDS))
          buffer should have size 3
        else
          fail("no completion signal is detected")

      }
      "with number of repeat should repeat value from this value as many as the provided parameter" in {
        val flux = Mono.just(randomValue).repeat(5)
        StepVerifier.create(flux)
          .expectNext(randomValue, randomValue, randomValue, randomValue, randomValue, randomValue)
          .verifyComplete()
      }
      "with number of repeat and predicate should repeat value from this value as many as provided parameter and as" +
        "long as the predicate returns true" in {
        val counter = new AtomicLong()
        val flux = Mono.just(randomValue).repeat(5, () => counter.get() < 3)
        val buffer = new LinkedBlockingQueue[Long]()
        val latch = new CountDownLatch(1)
        flux.subscribe(new BaseSubscriber[Long] {
          override def hookOnSubscribe(subscription: Subscription): Unit = subscription.request(Long.MaxValue)

          override def hookOnNext(value: Long): Unit = {
            counter.incrementAndGet()
            buffer.put(value)
          }

          override def hookOnComplete(): Unit = latch.countDown()
        })
        if (latch.await(1, TimeUnit.SECONDS))
          buffer should have size 3
        else
          fail("no completion signal is detected")
      }
    }

    ".repeatWhen should emit the value of this mono accompanied by the publisher" in {
      val flux = Mono.just(randomValue).repeatWhen((_: Flux[Long]) => Flux.just[Long](10, 20))
      StepVerifier.create(flux)
        .expectNext(randomValue, randomValue, randomValue)
        .verifyComplete()
    }

    //    Is this the right way to test?
    ".repeatWhenEmpty should emit resubscribe to this mono when the companion is empty" in {
      val mono = Mono.just(1).repeatWhenEmpty((_: Flux[Long]) => Flux.just(-1, -2, -3))
      StepVerifier.create(mono)
        .expectNext(1)
        .verifyComplete()
    }

    ".single" - {
      "should enforce the existence of element" in {
        val mono = Mono.just(randomValue).single()
        StepVerifier.create(mono)
          .expectNext(randomValue)
          .verifyComplete()
      }
      "should throw exception if it is empty" in {
        val mono = Mono.empty.single()
        StepVerifier.create(mono)
          .expectError(classOf[NoSuchElementException])
          .verify()
      }

    }

    ".subscribe" - {
      "without parameter should return Disposable" in {
        val x = Mono.just(randomValue).subscribe()
        x shouldBe a[Disposable]
      }
      "with consumer should invoke the consumer" in {
        val counter = new CountDownLatch(1)
        val disposable = Mono.just(randomValue).subscribe(_ => counter.countDown())
        disposable shouldBe a[Disposable]
        counter.await(1, TimeUnit.SECONDS) shouldBe true
      }
      "with consumer and error consumer should invoke the error consumer when error happen" in {
        val counter = new CountDownLatch(1)
        val disposable = Mono.error[Any](new RuntimeException()).subscribe(_ => (), _ => counter.countDown())
        disposable shouldBe a[Disposable]
        counter.await(1, TimeUnit.SECONDS) shouldBe true
      }
      "with consumer, error consumer and completeConsumer should invoke the completeConsumer when it's complete" in {
        val counter = new CountDownLatch(2)
        val disposable = Mono.just(randomValue).subscribe(_ => counter.countDown(), _ => (), counter.countDown())
        disposable shouldBe a[Disposable]
        counter.await(1, TimeUnit.SECONDS) shouldBe true
      }
      "with consumer, error consumer, completeConsumer and subscriptionConsumer should invoke the subscriptionConsumer when there is subscription" in {
        val counter = new CountDownLatch(3)
        val disposable = Mono.just(randomValue).subscribe(_ => counter.countDown(), _ => (), counter.countDown(), s => {
          s.request(1)
          counter.countDown()
        })
        disposable shouldBe a[Disposable]
        counter.await(1, TimeUnit.SECONDS) shouldBe true
      }
    }

    ".tag should call the underlying Mono.tag method" in {
      val jMono = spy(JMono.just(1))
      val flux = Mono(jMono)
      flux.tag("integer", "one")
      verify(jMono).tag("integer", "one")
    }

    ".take" - {
      "should complete after duration elapse" in {
        StepVerifier.withVirtualTime(() => Mono.delay(10 seconds).take(5 seconds))
          .thenAwait(JDuration.ofSeconds(5))
          .verifyComplete()
      }
      "with duration and scheduler should complete after duration elapse" in {
        StepVerifier.withVirtualTime(() => Mono.delay(10 seconds).take(5 seconds, Schedulers.parallel()))
          .thenAwait(JDuration.ofSeconds(5))
          .verifyComplete()
      }
    }

    ".takeUntilOther should complete if the companion publisher emit any signal first" in {
      StepVerifier.withVirtualTime(() => Mono.delay(10 seconds).takeUntilOther(Mono.just("a")))
        .verifyComplete()
    }

    ".then" - {
      "without parameter should only replays complete and error signals from this mono" in {
        val mono = Mono.just(randomValue).`then`()
        StepVerifier.create(mono)
          .verifyComplete()
      }
      "with other mono should ignore element from this mono and transform its completion signal into emission and " +
        "completion signal of the provided mono" in {
        val mono = Mono.just(randomValue).`then`(Mono.just("1"))
        StepVerifier.create(mono)
          .expectNext("1")
          .verifyComplete()
      }
    }

    ".thenEmpty should complete this mono then for a supplied publisher to also complete" in {
      val latch = new CountDownLatch(1)
      val mono = Mono.just(randomValue)
        .doOnSuccess(_ => latch.countDown())
        .thenEmpty(Mono.empty)
      StepVerifier.create(mono)
        .verifyComplete()
      latch.await(1, TimeUnit.SECONDS) shouldBe true
    }

    ".thenMany should ignore the element from this mono and transform the completion signal into a Flux that will emit " +
      "from the provided publisher when the publisher is provided " - {
      "directly" in {
        val flux = Mono.just(randomValue).thenMany(Flux.just(1, 2, 3))
        StepVerifier.create(flux)
          .expectNext(1, 2, 3)
          .verifyComplete()
      }
    }

    ".timeout" - {
      "should raise TimeoutException after duration elapse" in {
        StepVerifier.withVirtualTime(() => Mono.delay(10 seconds).timeout(5 seconds))
          .thenAwait(JDuration.ofSeconds(5))
          .expectError(classOf[TimeoutException])
          .verify()
      }
      "should fallback to the provided mono if the value doesn't arrive in given duration" in {
        StepVerifier.withVirtualTime(() => Mono.delay(10 seconds).timeout(5 seconds, Option(Mono.just(1L))))
          .thenAwait(5 seconds)
          .expectNext(1)
          .verifyComplete()
      }
      "with timeout and timer should signal TimeoutException if the item does not arrive before a given period" in {
        val timer = VirtualTimeScheduler.getOrSet()
        StepVerifier.withVirtualTime(() => Mono.delay(10 seconds).timeout(5 seconds, timer), () => timer, 1)
          .thenAwait(5 seconds)
          .expectError(classOf[TimeoutException])
          .verify()
      }
      "should raise TimeoutException if this mono has not emit value when the provided publisher has emit value" in {
        val mono = Mono.delay(10 seconds).timeout(Mono.just("whatever"))
        StepVerifier.create(mono)
          .expectError(classOf[TimeoutException])
          .verify()
      }
      "should fallback to the provided fallback mono if this mono does not emit value when the provided publisher emits value" in {
        val mono = Mono.delay(10 seconds).timeout(Mono.just("whatever"), Mono.just(-1L))
        StepVerifier.create(mono)
          .expectNext(-1)
          .verifyComplete()
      }
      "with timeout, fallback and timer should fallback to the given mono if the item does not arrive before a given period" in {
        val timer = VirtualTimeScheduler.getOrSet()
        StepVerifier.create(Mono.delay(10 seconds, timer)
          .timeout(5 seconds, Option(Mono.just(-1)), timer), 1)
          .`then`(() => timer.advanceTimeBy(5 seconds))
          .expectNext(-1)
          .verifyComplete()
      }
    }

    ".transform should transform this mono in order to generate a target mono" in {
      val mono = Mono.just(randomValue).transform(ml => Mono.just(ml.block().toString))
      StepVerifier.create(mono)
        .expectNext(randomValue.toString)
        .verifyComplete()
    }

    ".asJava should convert to java" in {
      Mono.just(randomValue).asJava() shouldBe a[JMono[_]]
    }

    ".apply should convert to scala" in {
      val mono = Mono(JMono.just(randomValue))
      mono shouldBe a[Mono[_]]
    }
  }


  private def createMono = {
    Mono.create[Long](monoSink => monoSink.success(randomValue))
  }
}

class MonoAsyncTest extends AsyncFreeSpec {
  "Mono" - {
    ".toFuture should convert this mono to future" in {
      val future: Future[Int] = Mono.just(1).toFuture
      future map { v => {
        assert(v == 1)
      }
      }
    }
  }
}