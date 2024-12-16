package com.youtoo
package ingestion
package store

import cats.implicits.*

import com.youtoo.cqrs.Codecs.given

import zio.test.*
import zio.test.Assertion.*
import zio.mock.Expectation.*
import zio.*
import zio.mock.*
import zio.jdbc.*
import zio.prelude.*
import com.youtoo.postgres.*
import com.youtoo.cqrs.*
import com.youtoo.cqrs.domain.*
import com.youtoo.cqrs.service.MockCQRSPersistence
import com.youtoo.ingestion.model.*

object FileEventStoreSpec extends MockSpecDefault {

  def spec = suite("FileEventStoreSpec")(
    testReadEventsId,
    testReadEventsByIdAndVersion,
    testReadEventsQueryOptions,
    testSaveEvent,
  ).provideSomeLayerShared(
    ZConnectionMock.pool(),
  )

  val discriminator = FileEvent.discriminator

  val testReadEventsId = test("readEvents by Id") {
    check(keyGen, fileEventSequenceGen) { (id, events) =>
      val mockEnv = MockCQRSPersistence.ReadEvents.Full.of(
        equalTo((id, discriminator)),
        value(events.toChunk),
      )

      val effect = for {
        store <- ZIO.service[FileEventStore]
        result <- store.readEvents(id).atomically
      } yield assert(result)(equalTo(events.some))

      effect.provideSomeLayer[ZConnectionPool](mockEnv.toLayer >>> FileEventStore.live())
    }
  }

  val testReadEventsByIdAndVersion = test("readEvents by Id and Version") {
    check(keyGen, versionGen, fileEventSequenceGen) { (id, version, events) =>
      val mockEnv = MockCQRSPersistence.ReadEvents.Snapshot.of(
        equalTo((id, discriminator, version)),
        value(events.toChunk),
      )

      val effect = for {
        store <- ZIO.service[FileEventStore]
        result <- store.readEvents(id, version).atomically
      } yield assert(result)(equalTo(events.some))

      effect.provideSomeLayer[ZConnectionPool](mockEnv.toLayer >>> FileEventStore.live())
    }
  }

  val testReadEventsQueryOptions = test("readEvents by Query and Options") {
    check(fileEventSequenceGen) { events =>
      val query = PersistenceQuery.condition()
      val options = FetchOptions()

      val mockEnv = MockCQRSPersistence.ReadEvents.FullArgs.of(
        equalTo((discriminator, query, options)),
        value(events.toChunk),
      )

      val effect = for {
        store <- ZIO.service[FileEventStore]
        result <- store.readEvents(query, options).atomically
      } yield assert(result)(equalTo(events.some))

      effect.provideSomeLayer[ZConnectionPool](mockEnv.toLayer >>> FileEventStore.live())
    }
  }

  val testSaveEvent = test("save an event") {
    check(keyGen, versionGen, fileEventGen) { (id, version, event) =>
      val change = Change(version, event)
      val returnId = 1L

      val mockEnv = MockCQRSPersistence.SaveEvent.of[(Key, Discriminator, Change[FileEvent]), Long](
        equalTo((id, discriminator, change)),
        value(returnId),
      )

      val effect = for {
        store <- ZIO.service[FileEventStore]
        result <- store.save(id, change).atomically
      } yield assert(result)(equalTo(returnId))

      effect.provideSomeLayer[ZConnectionPool](mockEnv.toLayer >>> FileEventStore.live())
    }
  }

}