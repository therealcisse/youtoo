package com.youtoo
package ingestion
package service

import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.common.*

import cats.implicits.*

import com.youtoo.cqrs.Codecs.given

import com.youtoo.postgres.*

import com.youtoo.ingestion.model.*

import zio.*
import zio.jdbc.*
import zio.prelude.*

import com.youtoo.ingestion.store.*
import com.youtoo.cqrs.*
import com.youtoo.cqrs.domain.*

trait FileService {
  def addFile(
    provider: Provider.Id,
    id: IngestionFile.Id,
    name: IngestionFile.Name,
    metadata: IngestionFile.Metadata,
    sig: IngestionFile.Sig,
  ): Task[Unit]

  def loadNamed(name: IngestionFile.Name): Task[Option[IngestionFile]]
  def loadSig(sig: IngestionFile.Sig): Task[Option[IngestionFile]]

  def load(id: IngestionFile.Id): Task[Option[IngestionFile]]

}

object FileService {
  inline def addFile(
    provider: Provider.Id,
    id: IngestionFile.Id,
    name: IngestionFile.Name,
    metadata: IngestionFile.Metadata,
    sig: IngestionFile.Sig,
  ): RIO[FileService, Unit] =
    ZIO.serviceWithZIO(_.addFile(provider, id, name, metadata, sig))

  inline def loadNamed(name: IngestionFile.Name): RIO[FileService, Option[IngestionFile]] =
    ZIO.serviceWithZIO(_.loadNamed(name))

  inline def loadSig(sig: IngestionFile.Sig): RIO[FileService, Option[IngestionFile]] =
    ZIO.serviceWithZIO(_.loadSig(sig))

  inline def load(id: IngestionFile.Id): RIO[FileService, Option[IngestionFile]] =
    ZIO.serviceWithZIO(_.load(id))

  def live(): ZLayer[
    ZConnectionPool & FileEventStore & Tracing,
    Throwable,
    FileService,
  ] =
    ZLayer.fromFunction {
      (
        pool: ZConnectionPool,
        eventStore: FileEventStore,
        tracing: Tracing,
      ) =>
        new FileService.Live(pool, eventStore).traced(tracing)

    }

  class Live(pool: ZConnectionPool, eventStore: FileEventStore) extends FileService { self =>
    def addFile(
      provider: Provider.Id,
      id: IngestionFile.Id,
      name: IngestionFile.Name,
      metadata: IngestionFile.Metadata,
      sig: IngestionFile.Sig,
    ): Task[Unit] =
      atomically {
        val cmd = FileCommand.AddFile(provider, id, name, metadata, sig)
        val evnts = CmdHandler.applyCmd(cmd)

        ZIO.foreachDiscard(evnts) { e =>
          for {
            version <- Version.gen
            ch = Change(version = version, payload = e)
            _ <- eventStore.save(id = id.asKey, ch)
          } yield ()

        }

      }.provideEnvironment(ZEnvironment(pool))

    def loadNamed(name: IngestionFile.Name): Task[Option[IngestionFile]] =
      given FileEvent.LoadIngestionFileByName(name)

      atomically {
        for {
          events <- eventStore.readEvents(
            query = PersistenceQuery.condition(
              namespace = Namespace(0).some,
              props = NonEmptyList(EventProperty("name", name.value)).some,
            ),
            options = FetchOptions(),
          )
          inn = events flatMap { es =>
            EventHandler.applyEvents(es)
          }

        } yield inn

      }.provideEnvironment(ZEnvironment(pool))

    def loadSig(sig: IngestionFile.Sig): Task[Option[IngestionFile]] =
      given FileEvent.LoadIngestionFileBySig(sig)

      atomically {
        for {
          events <- eventStore.readEvents(
            query = PersistenceQuery.condition(
              namespace = Namespace(0).some,
              props = NonEmptyList(EventProperty("sig", sig.value)).some,
            ),
            options = FetchOptions(),
          )
          inn = events flatMap { es =>
            EventHandler.applyEvents(es)
          }

        } yield inn

      }.provideEnvironment(ZEnvironment(pool))

    def load(id: IngestionFile.Id): Task[Option[IngestionFile]] =
      given FileEvent.LoadIngestionFile(id)

      val key = id.asKey

      atomically {
        for {
          events <- eventStore.readEvents(key)
          inn = events flatMap { es =>
            EventHandler.applyEvents(es)
          }

        } yield inn

      }.provideEnvironment(ZEnvironment(pool))

    def traced(tracing: Tracing): FileService =
      new FileService {
        def addFile(
          provider: Provider.Id,
          id: IngestionFile.Id,
          name: IngestionFile.Name,
          metadata: IngestionFile.Metadata,
          sig: IngestionFile.Sig,
        ): Task[Unit] = self.addFile(provider, id, name, metadata, sig) @@ tracing.aspects.span(
          "FileService.addFile",
          attributes = Attributes(
            Attribute.long("providerId", provider.asKey.value),
            Attribute.long("id", id.asKey.value),
            Attribute.string("name", name.value),
            Attribute.string("sig", sig.value),
          ),
        )

        def loadNamed(name: IngestionFile.Name): Task[Option[IngestionFile]] =
          self.loadNamed(name) @@ tracing.aspects.span(
            "FileService.loadNamed",
            attributes = Attributes(Attribute.string("name", name.value)),
          )
        def loadSig(sig: IngestionFile.Sig): Task[Option[IngestionFile]] =
          self.loadSig(sig) @@ tracing.aspects.span(
            "FileService.loadSig",
            attributes = Attributes(Attribute.string("sig", sig.value)),
          )

        def load(id: IngestionFile.Id): Task[Option[IngestionFile]] =
          self.load(id) @@ tracing.aspects.span(
            "FileService.load",
            attributes = Attributes(Attribute.long("id", id.asKey.value)),
          )
      }

  }

}
