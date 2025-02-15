package com.youtoo
package migration

import scala.language.future

import zio.*
import zio.jdbc.*
import zio.prelude.*
import zio.stream.*
import zio.logging.*
import zio.logging.backend.SLF4J

import cats.implicits.*

import com.youtoo.postgres.*
import com.youtoo.cqrs.*
import com.youtoo.cqrs.store.*
import com.youtoo.cqrs.service.*

import com.youtoo.migration.model.*
import com.youtoo.migration.service.*
import com.youtoo.migration.repository.*
import com.youtoo.cqrs.service.postgres.*
import com.youtoo.migration.store.*
import com.youtoo.postgres.config.*

import com.youtoo.std.interruption.*
import com.youtoo.std.healthcheck.*

import zio.http.{Version as _, *}
import zio.http.netty.NettyConfig
import zio.schema.codec.BinaryCodec
import java.nio.charset.StandardCharsets

import com.youtoo.observability.RestEndpoint
import com.youtoo.observability.RestEndpoint.*
import com.youtoo.observability.otel.OtelSdk

import zio.telemetry.opentelemetry.metrics.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.baggage.Baggage

object MigrationApp extends ZIOApp {
  import com.youtoo.cqrs.Codecs.json.given

  inline val FetchSize = 1_000L

  object Port extends Newtype[Int] {
    extension (a: Type) def value: Int = unwrap(a)
  }

  given Config[Port.Type] = Config.int.nested("migration_app_port").withDefault(8181).map(Port(_))

  type Environment =
    FlywayMigration & ZConnectionPool & CQRSPersistence & SnapshotStore & MigrationEventStore & MigrationCQRS & Server & Server.Config & NettyConfig & MigrationService & MigrationRepository & SnapshotStrategy.Factory & DataMigration & Interrupter & Healthcheck & Tracing & Baggage & Meter

  given environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  private val instrumentationScopeName = "com.youtoo.migration.MigrationApp"
  private val resourceName = "migration"

  private val configLayer = ZLayer {
    for {
      port <- ZIO.config[Port.Type]

      config = Server.Config.default.port(port.value)
    } yield config

  }

  private val nettyConfig = NettyConfig.default
    .leakDetection(NettyConfig.LeakDetectionLevel.DISABLED)

  private val nettyConfigLayer = ZLayer.succeed(nettyConfig)

  val bootstrap: ZLayer[Any, Nothing, Environment] =
    Log.layer >>> Runtime.disableFlags(
      RuntimeFlag.FiberRoots,
    ) ++ Runtime.enableRuntimeMetrics ++ Runtime.enableAutoBlockingExecutor ++ Runtime.enableFlags(
      RuntimeFlag.EagerShiftBack,
    ) ++
      ZLayer
        .make[Environment](
          zio.metrics.jvm.DefaultJvmMetrics.live.unit,
          DatabaseConfig.pool,
          PostgresCQRSPersistence.live(),
          FlywayMigration.live(),
          SnapshotStore.live(),
          MigrationEventStore.live(),
          MigrationService.live(),
          MigrationRepository.live(),
          MigrationCQRS.live(),
          configLayer,
          nettyConfigLayer,
          Server.customized,
          SnapshotStrategy.live(),
          DataMigration.live(),
          Interrupter.live(),
          Healthcheck.live(),
          OtelSdk.custom(resourceName),
          OpenTelemetry.tracing(instrumentationScopeName),
          OpenTelemetry.metrics(instrumentationScopeName),
          OpenTelemetry.logging(instrumentationScopeName),
          OpenTelemetry.baggage(),
          // OpenTelemetry.zioMetrics,
          OpenTelemetry.contextZIO,
        )
        .orDie ++ Runtime.setConfigProvider(ConfigProvider.envProvider)

  val endpoint = RestEndpoint(RestEndpoint.Service("migration"))

  val routes: Routes[Environment & Scope, Response] = Routes(
    Method.POST / "migration" / "dataload" -> handler { (req: Request) =>
      endpoint.boundary("dataload_migrations", req) {

        req.body.fromBody[List[Key]] flatMap (ids =>
          for {
            migrations <- ZIO.foreachPar(ids) { key =>
              MigrationService.load(Migration.Id(key))
            }

            ins = migrations.mapFilter(identity)

            bytes = ins
              .map(in => String(summon[BinaryCodec[Migration]].encode(in).toArray, StandardCharsets.UTF_8))
              .mkString("[", ",", "]")

            resp = Response(
              Status.Ok,
              Headers(Header.ContentType(MediaType.application.json).untyped),
              Body.fromCharSequence(s"""{"migrations":$bytes}"""),
            )

          } yield resp
        )
      }

    },
    Method.GET / "migration" -> handler { (req: Request) =>
      endpoint.boundary("get_migrations", req) {
        val offset = req.queryParamTo[Long]("offset").toOption
        val limit = req.queryParamToOrElse[Long]("limit", FetchSize)

        atomically {

          MigrationService.loadMany(offset = offset.map(Key.apply), limit) map { ids =>
            val bytes = String(summon[BinaryCodec[Chunk[Key]]].encode(ids).toArray, StandardCharsets.UTF_8)

            val nextOffset =
              (if ids.size < limit then None else ids.minOption).map(id => s""","nextOffset":"$id"""").getOrElse("")

            Response(
              Status.Ok,
              Headers(Header.ContentType(MediaType.application.json).untyped),
              Body.fromCharSequence(s"""{"ids":$bytes$nextOffset}"""),
            )

          }

        }
      }

    },
    Method.GET / "migration" / long("id") -> handler { (id: Long, req: Request) =>
      endpoint.boundary("get_migration", req) {
        val key = Key(id)

        MigrationService.load(Migration.Id(key)) map {
          case Some(migration) =>
            val bytes = summon[BinaryCodec[Migration]].encode(migration)

            Response(
              Status.Ok,
              Headers(Header.ContentType(MediaType.application.json).untyped),
              Body.fromChunk(bytes),
            )

          case None => Response.notFound
        }
      }

    },
    Method.POST / "migration" -> handler { (req: Request) =>
      endpoint.boundary("add_migraion", req) {
        for {
          id <- Key.gen

          timestamp <- Timestamp.gen

          _ <- MigrationCQRS.add(id, MigrationCommand.RegisterMigration(Migration.Id(id), timestamp))

          opt <- MigrationService.load(Migration.Id(id))

          _ <- opt.fold(ZIO.unit) { migration =>
            atomically {
              MigrationService.save(migration)
            }
          }

        } yield Response.json(s"""{"id":"$id"}""")

      }
    },
    Method.POST / "migration" / long("id") / "run" -> handler { (id: Long, req: Request) =>
      endpoint.boundary("run_migration", req) {
        val numKeys = req.queryParamToOrElse[Long]("numKeys", 10L)

        val processor: ZLayer[Tracing, Nothing, DataMigration.Processor] = ZLayer.fromFunction { (tracing: Tracing) =>
          new DataMigration.Processor {
            def count(): Task[Long] = ZIO.succeed(numKeys)
            def load(): ZStream[Any, Throwable, Key] = ZStream((0L until numKeys).map(i => Key(i))*)
            def process(key: Key): Task[Unit] = Log.info(s"Processed $key").provide(ZLayer.succeed(tracing))

          }
        }

        val op =
          DataMigration
            .run(id = Migration.Id(Key(id)))
            .provideSomeLayer[Tracing & MigrationCQRS & DataMigration & MigrationService & Scope](processor)

        op `as` Response.ok
      }

    },
    Method.DELETE / "migration" / long("id") / "stop" -> handler { (id: Long, req: Request) =>
      endpoint.boundary("stop_migration", req) {
        Interrupter.interrupt(id = Key(id)) `as` Response.ok
      }

    },
  )

  val run: URIO[Environment & Scope, ExitCode] =
    (
      for {
        _ <- endpoint.uptime

        config <- ZIO.config[DatabaseConfig]
        _ <- FlywayMigration.run(config)

        _ <- Server.serve(routes)
      } yield ()
    ).exitCode

}
