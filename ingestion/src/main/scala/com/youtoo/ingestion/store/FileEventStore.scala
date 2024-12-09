package com.youtoo
package ingestion
package store

import com.youtoo.ingestion.model.*

import com.youtoo.cqrs.*
import com.youtoo.cqrs.domain.*
import com.youtoo.cqrs.store.*

import zio.*
import zio.prelude.*

import zio.jdbc.*
import com.youtoo.cqrs.service.*

import com.youtoo.cqrs.Codecs.*

trait FileEventStore extends EventStore[FileEvent] {}

object FileEventStore {

  def live(): ZLayer[CQRSPersistence, Throwable, FileEventStore] =
    ZLayer.fromFunction { (persistence: CQRSPersistence) =>
      new FileEventStore {
        def readEvents(id: Key): RIO[ZConnection, Option[NonEmptyList[Change[FileEvent]]]] =
          persistence.readEvents[FileEvent](id, FileEvent.discriminator).map { es =>
            NonEmptyList.fromIterableOption(es)
          }

        def readEvents(
          id: Key,
          snapshotVersion: Version,
        ): RIO[ZConnection, Option[NonEmptyList[Change[FileEvent]]]] =
          persistence.readEvents[FileEvent](id, FileEvent.discriminator, snapshotVersion).map { es =>
            NonEmptyList.fromIterableOption(es)
          }

        def readEvents(
          ns: Option[NonEmptyList[Namespace]],
          hierarchy: Option[Hierarchy],
          props: Option[NonEmptyList[EventProperty]],
        ): RIO[ZConnection, Option[NonEmptyList[Change[FileEvent]]]] =
          persistence.readEvents[FileEvent](FileEvent.discriminator, ns, hierarchy, props).map { es =>
            NonEmptyList.fromIterableOption(es)
          }

        def readEvents(
          snapshotVersion: Version,
          ns: Option[NonEmptyList[Namespace]],
          hierarchy: Option[Hierarchy],
          props: Option[NonEmptyList[EventProperty]],
        ): RIO[ZConnection, Option[NonEmptyList[Change[FileEvent]]]] =
          persistence.readEvents[FileEvent](FileEvent.discriminator, snapshotVersion, ns, hierarchy, props).map { es =>
            NonEmptyList.fromIterableOption(es)
          }

        def save(id: Key, event: Change[FileEvent]): RIO[ZConnection, Long] =
          persistence.saveEvent(id, FileEvent.discriminator, event)

      }
    }

}
