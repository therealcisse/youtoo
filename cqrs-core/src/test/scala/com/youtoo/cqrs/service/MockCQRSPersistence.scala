package com.youtoo
package cqrs
package service

import zio.mock.*

import com.youtoo.cqrs.domain.*

import zio.*
import zio.jdbc.*
import zio.prelude.*
import zio.schema.codec.*

object MockCQRSPersistence extends Mock[CQRSPersistence] {
  object ReadEvents {
    object Full extends Poly.Effect.Output[(Key, Discriminator), Throwable]
    object Snapshot extends Poly.Effect.Output[(Key, Discriminator, Version), Throwable]

    object FullArgs
        extends Poly.Effect.Output[
          (Discriminator, Option[NonEmptyList[Namespace]], Option[Hierarchy], Option[NonEmptyList[EventProperty]]),
          Throwable,
        ]
    object SnapshotArgs
        extends Poly.Effect.Output[
          (
            Discriminator,
            Version,
            Option[NonEmptyList[Namespace]],
            Option[Hierarchy],
            Option[NonEmptyList[EventProperty]],
          ),
          Throwable,
        ]
  }

  object SaveEvent extends Poly.Effect.InputOutput[Throwable]

  object ReadSnapshot extends Effect[Key, Throwable, Option[Version]]
  object SaveSnapshot extends Effect[(Key, Version), Throwable, Long]

  val compose: URLayer[Proxy, CQRSPersistence] =
    ZLayer {
      for {
        proxy <- ZIO.service[Proxy]
      } yield new CQRSPersistence {
        def readEvents[Event: BinaryCodec: Tag: MetaInfo](
          id: Key,
          discriminator: Discriminator,
          snapshotVersion: Version,
        ): ZIO[ZConnection, Throwable, Chunk[Change[Event]]] =
          proxy(ReadEvents.Snapshot.of[Chunk[Change[Event]]], (id, discriminator, snapshotVersion))

        def readEvents[Event: BinaryCodec: Tag: MetaInfo](
          id: Key,
          discriminator: Discriminator,
        ): ZIO[ZConnection, Throwable, Chunk[Change[Event]]] =
          proxy(ReadEvents.Full.of[Chunk[Change[Event]]], (id, discriminator))

        def readEvents[Event: BinaryCodec: Tag: MetaInfo](
          discriminator: Discriminator,
          snapshotVersion: Version,
          ns: Option[NonEmptyList[Namespace]],
          hierarchy: Option[Hierarchy],
          props: Option[NonEmptyList[EventProperty]],
        ): ZIO[ZConnection, Throwable, Chunk[Change[Event]]] =
          proxy(
            ReadEvents.SnapshotArgs.of[Chunk[Change[Event]]],
            (discriminator, snapshotVersion, ns, hierarchy, props),
          )

        def readEvents[Event: BinaryCodec: Tag: MetaInfo](
          discriminator: Discriminator,
          ns: Option[NonEmptyList[Namespace]],
          hierarchy: Option[Hierarchy],
          props: Option[NonEmptyList[EventProperty]],
        ): ZIO[ZConnection, Throwable, Chunk[Change[Event]]] =
          proxy(ReadEvents.FullArgs.of[Chunk[Change[Event]]], (discriminator, ns, hierarchy, props))

        def saveEvent[Event: BinaryCodec: MetaInfo: Tag](
          id: Key,
          discriminator: Discriminator,
          event: Change[Event],
        ): ZIO[ZConnection, Throwable, Long] =
          proxy(SaveEvent.of[(Key, Discriminator, Change[Event]), Long], (id, discriminator, event))

        def readSnapshot(id: Key): ZIO[ZConnection, Throwable, Option[Version]] =
          proxy(ReadSnapshot, id)

        def saveSnapshot(id: Key, version: Version): ZIO[ZConnection, Throwable, Long] =
          proxy(SaveSnapshot, (id, version))
      }
    }
}
