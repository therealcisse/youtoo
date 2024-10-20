package com.youtoo.cqrs
package example

import zio.*

import cats.implicits.*

import zio.jdbc.*
import zio.prelude.*

import com.youtoo.cqrs.Codecs.*

import com.youtoo.cqrs.store.*
import com.youtoo.cqrs.domain.*
import com.youtoo.cqrs.example.model.*
import com.youtoo.cqrs.service.*
import com.youtoo.cqrs.example.service.*
import com.youtoo.cqrs.example.store.*
import zio.metrics.Metric

trait IngestionCQRS extends CQRS[Ingestion, IngestionEvent, IngestionCommand] {}

object IngestionCQRS {

  object metrics {
    val adds = Metric.counter("Ingestion.additions").fromConst(1)
    val loads = Metric.counter("Ingestion.loads").fromConst(1)

  }

  inline def add(id: Key, cmd: IngestionCommand)(using
    Cmd: IngestionCommandHandler,
  ): RIO[IngestionCQRS, Unit] = ZIO.serviceWithZIO[IngestionCQRS](_.add(id, cmd)) @@ metrics.adds

  inline def load(id: Key)(using
    Evt: IngestionEventHandler,
  ): RIO[IngestionCQRS, Option[Ingestion]] =
    ZIO.serviceWithZIO[IngestionCQRS](_.load(id)) @@ metrics.loads

  def live(): ZLayer[
    ZConnectionPool & IngestionCheckpointer & IngestionProvider & IngestionEventStore & SnapshotStore &
      SnapshotStrategy.Factory,
    Throwable,
    IngestionCQRS,
  ] =
    ZLayer.fromFunction {
      (
        factory: SnapshotStrategy.Factory,
        checkpointer: IngestionCheckpointer,
        provider: IngestionProvider,
        eventStore: IngestionEventStore,
        snapshotStore: SnapshotStore,
        pool: ZConnectionPool,
      ) =>
        ZLayer {

          for {
            strategy <- factory.create(IngestionEvent.discriminator)

          } yield new IngestionCQRS {
            private val Cmd = summon[CmdHandler[IngestionCommand, IngestionEvent]]
            private val Evt = summon[EventHandler[IngestionEvent, Ingestion]]

            def add(id: Key, cmd: IngestionCommand): Task[Unit] =
              atomically {
                val evnts = Cmd.applyCmd(cmd)

                ZIO.foreachDiscard(evnts) { e =>
                  for {
                    version <- Version.gen
                    ch = Change(version = version, payload = e)
                    _ <- eventStore.save(id = id, ch)
                  } yield ()
                }
              }.provideEnvironment(ZEnvironment(pool))

            def load(id: Key): Task[Option[Ingestion]] =
              atomically {
                val deps = (
                  provider.load(id) <&> snapshotStore.readSnapshot(id)
                ).map(_.tupled)

                val o = deps flatMap {
                  case None =>
                    for {
                      events <- eventStore.readEvents(id)
                      inn = events map { es =>
                        (
                          Evt.applyEvents(es),
                          es.maxBy(_.version),
                          es.size,
                          None,
                        )
                      }

                    } yield inn

                  case Some((in, version)) =>
                    val events = eventStore.readEvents(id, snapshotVersion = version)

                    events map (_.map { es =>
                      (
                        Evt.applyEvents(in, es),
                        es.maxBy(_.version),
                        es.size,
                        version.some,
                      )
                    })

                }

                o flatMap (_.fold(ZIO.none) {
                  case (inn, ch, size, version) if strategy(version, size) =>
                    for {
                      _ <- checkpointer.save(inn)
                      _ <- snapshotStore.save(id = id, version = ch.version)

                    } yield inn.some

                  case (inn, _, _, _) => ZIO.some(inn)
                })
              }.provideEnvironment(ZEnvironment(pool))

          }
        }

    }.flatten

}
