/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.s3.scaladsl

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes
import akka.stream.alpakka.s3._
import akka.stream.alpakka.testkit.scaladsl.LogCapturing
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class OneSubscriberSpec
    extends AnyFlatSpecLike
    with BeforeAndAfterAll
    with Matchers
    with ScalaFutures
    with OptionValues
    with LogCapturing {

  implicit val actorSystem: ActorSystem = ActorSystem(
    "OneSubscriberSpec",
    config().withFallback(ConfigFactory.load())
  )

  def config() = ConfigFactory.parseString("""
                                             |alpakka.s3.aws.region {
                                             |  provider = static
                                             |  default-region = "us-east-1"
                                             |}
    """.stripMargin)

  implicit val materializer = ActorMaterializer()
  implicit val ec = materializer.executionContext

  implicit val defaultPatience: PatienceConfig = PatienceConfig(90.seconds, 100.millis)

  val defaultBucket = "my-test-us-east-1"

  val objectKey = "test"

  val objectValue = "Some String"

  override protected def afterAll(): Unit = TestKit.shutdownActorSystem(actorSystem)

  it should "s3 accumulator test" in {
    val objectKey = "putTest"
    val bytes = ByteString(objectValue)
    val data = Source.single(ByteString(objectValue))

    val sha256 = {
      import java.nio.charset.StandardCharsets
      import java.security.MessageDigest
      val digest = MessageDigest.getInstance("SHA-256")
      new String(digest.digest(objectValue.getBytes(StandardCharsets.UTF_8)))
    }

    var acc: Accumulator[ByteString, Right[Nothing, ObjectMetadata]] = Accumulator.source[ByteString].mapFuture {
      source =>
        S3.putObject(
            bucket = defaultBucket,
            key = objectKey,
            data = source,
            contentLength = bytes.length,
            contentType = ContentTypes.`application/octet-stream`,
            s3Headers = S3Headers().withCustomHeaders(Map("x-amz-content-sha256" -> sha256))
          )
          .map { metadata =>
            metadata
          }
          .runWith(Sink.head)
          .map(Right.apply)
    }

    var result = acc.run(data)

    result.futureValue.right.get.eTag should not be empty
  }
}

object Accumulator {
  def source[E]: Accumulator[E, Source[E, _]] = {
    // If Akka streams ever provides Sink.source(), we should use that instead.
    // https://github.com/akka/akka/issues/18406
    new SinkAccumulator(
      Sink
        .asPublisher[E](fanout = false)
        .mapMaterializedValue(publisher => Future.successful(Source.fromPublisher(publisher)))
    )
  }
}

sealed trait Accumulator[-E, +A] {

  /**
   * Map the result of this accumulator to something else.
   */
  def map[B](f: A => B)(implicit executor: ExecutionContext): Accumulator[E, B]

  /**
   * Map the result of this accumulator to a future of something else.
   */
  def mapFuture[B](f: A => Future[B])(implicit executor: ExecutionContext): Accumulator[E, B]

  /**
   * Recover from errors encountered by this accumulator.
   */
  def recover[B >: A](pf: PartialFunction[Throwable, B])(implicit executor: ExecutionContext): Accumulator[E, B]

  /**
   * Recover from errors encountered by this accumulator.
   */
  def recoverWith[B >: A](pf: PartialFunction[Throwable, Future[B]])(
    implicit executor: ExecutionContext
  ): Accumulator[E, B]

  /**
   * Return a new accumulator that first feeds the input through the given flow before it goes through this accumulator.
   */
  def through[F](flow: Flow[F, E, _]): Accumulator[F, A]

  /**
   * Right associative operator alias for through.
   *
   * This can be used for a more fluent DSL that matches the flow of the data, for example:
   *
   * {{{
   *   val intAccumulator: Accumulator[Int, Unit] = ...
   *   val toInt = Flow[String].map(_.toInt)
   *   val stringAccumulator = toInt ~>: intAccumulator
   * }}}
   */
  def ~>:[F](flow: Flow[F, E, _]): Accumulator[F, A] = through(flow)

  /**
   * Run this accumulator by feeding in the given source.
   */
  def run(source: Source[E, _])(implicit materializer: Materializer): Future[A]

  /**
   * Run this accumulator by feeding nothing into it.
   */
  def run()(implicit materializer: Materializer): Future[A]

  /**
   * Run this accumulator by feeding a single element into it.
   */
  def run(elem: E)(implicit materializer: Materializer): Future[A]

  /**
   * Right associative operator alias for run.
   *
   * This can be used for a more fluent DSL that matches the flow of the data, for example:
   *
   * {{{
   *   val intAccumulator: Accumulator[Int, Int] = ...
   *   val source = Source(1 to 3)
   *   val intFuture = source ~>: intAccumulator
   * }}}
   */
  def ~>:(source: Source[E, _])(implicit materializer: Materializer): Future[A] = run(source)

  /**
   * Convert this accumulator to a Sink that gets materialised to a Future.
   */
  def toSink: Sink[E, Future[A]]

}

/**
 * An accumulator backed by a sink.
 *
 * This is essentially a lightweight wrapper around a Sink that gets materialised to a Future, but provides convenient
 * methods for working directly with that future as well as transforming the input.
 */
private class SinkAccumulator[-E, +A](wrappedSink: => Sink[E, Future[A]]) extends Accumulator[E, A] {
  private lazy val sink: Sink[E, Future[A]] = wrappedSink

  def map[B](f: A => B)(implicit executor: ExecutionContext): Accumulator[E, B] =
    new SinkAccumulator(sink.mapMaterializedValue(_.map(f)))

  def mapFuture[B](f: A => Future[B])(implicit executor: ExecutionContext): Accumulator[E, B] =
    new SinkAccumulator(sink.mapMaterializedValue(_.flatMap(f)))

  def recover[B >: A](pf: PartialFunction[Throwable, B])(implicit executor: ExecutionContext): Accumulator[E, B] =
    new SinkAccumulator(sink.mapMaterializedValue(_.recover(pf)))

  def recoverWith[B >: A](
                           pf: PartialFunction[Throwable, Future[B]]
                         )(implicit executor: ExecutionContext): Accumulator[E, B] =
    new SinkAccumulator(sink.mapMaterializedValue(_.recoverWith(pf)))

  def through[F](flow: Flow[F, E, _]): Accumulator[F, A] = new SinkAccumulator(flow.toMat(sink)(Keep.right))

  def run(source: Source[E, _])(implicit materializer: Materializer): Future[A] = source.toMat(sink)(Keep.right).run()
  def run()(implicit materializer: Materializer): Future[A] = run(Source.empty)
  def run(elem: E)(implicit materializer: Materializer): Future[A] = run(Source.single(elem))

  def toSink: Sink[E, Future[A]] = sink

}

private class StrictAccumulator[-E, +A](handler: Option[E] => Future[A], val toSink: Sink[E, Future[A]])
  extends Accumulator[E, A] {
  private def mapMat[B](f: Future[A] => Future[B])(implicit executor: ExecutionContext): StrictAccumulator[E, B] = {
    new StrictAccumulator(handler.andThen(f), toSink.mapMaterializedValue(f))
  }

  override def map[B](f: A => B)(implicit executor: ExecutionContext): Accumulator[E, B] =
    mapMat { future =>
      future.value match {
        case Some(Success(a)) => Future.fromTry(Try(f(a))) // optimize already completed case
        case _ => future.map(f)
      }
    }

  def mapFuture[B](f: A => Future[B])(implicit executor: ExecutionContext): Accumulator[E, B] =
    mapMat { future =>
      future.value match {
        case Some(Success(a)) => // optimize already completed case
          Try(f(a)) match {
            case Success(fut) => fut
            case Failure(ex) => Future.failed(ex)
          }
        case _ => future.flatMap(f)
      }
    }

  def recover[B >: A](pf: PartialFunction[Throwable, B])(implicit executor: ExecutionContext): Accumulator[E, B] =
    mapMat { future =>
      future.value match {
        case Some(Success(_)) => future // optimize already completed case
        case _ => future.recover(pf)
      }
    }

  def recoverWith[B >: A](
                           pf: PartialFunction[Throwable, Future[B]]
                         )(implicit executor: ExecutionContext): Accumulator[E, B] =
    mapMat { future =>
      future.value match {
        case Some(Success(_)) => future // optimize already completed case
        case _ => future.recoverWith(pf)
      }
    }

  override def through[F](flow: Flow[F, E, _]): Accumulator[F, A] = {
    new SinkAccumulator(flow.toMat(toSink)(Keep.right))
  }

  override def run(source: Source[E, _])(implicit materializer: Materializer): Future[A] = source.runWith(toSink)
  override def run()(implicit materializer: Materializer): Future[A] = handler(None)
  override def run(elem: E)(implicit materializer: Materializer): Future[A] = handler(Some(elem))
}
