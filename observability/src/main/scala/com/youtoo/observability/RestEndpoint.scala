package com.youtoo
package observability

import cats.implicits.*

import io.opentelemetry.api.trace.*

import zio.*
import zio.telemetry.opentelemetry.metrics.*
import zio.telemetry.opentelemetry.common.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.baggage.Baggage

import zio.http.*

import java.lang as jl;

import zio.schema.codec.*

class RestEndpoint(service: RestEndpoint.Service) {
  lazy val requestLatency: ZIO[Meter, Nothing, Histogram[Double]] =
    ZIO.serviceWithZIO[Meter] { meter =>
      meter.histogram(
        name = s"youtoo_${service.name}_http_request_duration",
        unit = "ms".some,
        description = "Request latency in milliseconds".some,
        boundaries = Chunk.iterate(1.0, 10)(_ + 1.0).some,
      )
    }

  lazy val requestCount: ZIO[Meter, Nothing, Counter[Long]] =
    ZIO.serviceWithZIO[Meter] { meter =>
      meter.counter(
        name = s"youtoo_${service.name}_http_requests_total",
        description = "Total number of HTTP requests".some,
      )
    }

  lazy val activeRequests: ZIO[Meter, Nothing, UpDownCounter[Long]] =
    ZIO.serviceWithZIO[Meter] { meter =>
      meter.upDownCounter(s"youtoo_${service.name}_http_active_requests", description = "Number of active HTTP requests".some)
    }

  lazy val uptime: ZIO[Meter & Scope, Throwable, Unit] =
    ZIO.serviceWithZIO[Meter] { meter =>
      meter.observableGauge(
        s"youtoo_${service.name}_application_uptime_seconds",
        description = "The uptime of the application in seconds".some,
      ) { guage =>
        val uptimeInSeconds = (jl.System.currentTimeMillis() / 1000.0) - startEpochSeconds
        guage.record(uptimeInSeconds, Attributes.empty)
      }
    }

  lazy val startEpochSeconds: Long = jl.System.currentTimeMillis() / 1000L


  inline def boundary[R](tag: String, request: Request)(
    body: ZIO[R, Throwable, Response],
  ): URIO[R & Baggage & Tracing & Meter, Response] =
    ZIO.serviceWithZIO[Tracing] { (tracing: Tracing) =>

      val effect = body catchAllCause {
        _.failureOrCause.fold(
          { case e =>
            for {
              _ <- Log.error(s"$service - [$tag] - Request failed", e)

              span <- tracing.getCurrentSpanUnsafe

              _ <- ZIO.attempt {
                span.setStatus(StatusCode.ERROR)
                span.recordException(e)

              }.ignoreLogged

            } yield Response.internalServerError

          },
          Exit.failCause,
        )

      }

      val startTime = jl.System.nanoTime()

      val op = for {
        _ <- Log.debug(s"[$service] - started $tag")

        activeCounter <- activeRequests
        _ <- activeCounter.inc()

        response <- effect.ensuring {
          activeCounter.dec()

        }

        endTime = jl.System.nanoTime()

        attributes = Attributes(
          Attribute.string("http_method", request.method.name),
          Attribute.string("http_path", tag),
          Attribute.long("status_code", response.status.code.toLong),
        )

        latency <- requestLatency
        _ <- latency.record((endTime - startTime).toDouble / 1e9, attributes)

        requestCounter <- requestCount
        _ <- requestCounter.inc(attributes)

        _ <- Log.debug(s"[$service] - completed $tag")
      } yield response

      op @@ tracing.aspects.root(
        tag,
        attributes = Attributes(Attribute.string("version", ProjectInfo.version)),
        spanKind = SpanKind.INTERNAL,
      )

    }

}

object RestEndpoint {
  import zio.prelude.*

  type Service = Service.Type

  object Service extends Newtype[String] {
    extension (a: Type) def name: String = unwrap(a)
  }

  extension (body: Body)
    inline def fromBody[A: {BinaryCodec, Tag}]: RIO[Tracing, A] =
      for {
        ch <- body.asChunk
        a <- ZIO.fromEither {
          summon[BinaryCodec[A]].decode(ch)

        }.tapError { e =>
          Log.error(s"Error decoding entity ${Tag[A]}", e)
        }

      } yield a

}

