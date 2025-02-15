package com.youtoo
package cqrs
package service
package memory

import com.youtoo.postgres.*
import com.youtoo.cqrs.service.*

import com.youtoo.cqrs.domain.*

import com.youtoo.cqrs.Codecs.given

import cats.implicits.*

import zio.*
import zio.prelude.*
import zio.test.*
import zio.test.Assertion.*
import zio.schema.*

import zio.jdbc.*

import zio.telemetry.opentelemetry.tracing.*

object MemoryCQRSPersistenceSpec extends ZIOSpecDefault {
  case class DummyEvent(id: String)

  inline val grandParentIdXXX = 11L
  inline val parentIdXXX = 22L

  object DummyEvent {
    given Schema[DummyEvent] = DeriveSchema.gen[DummyEvent]

  }

  given MetaInfo[DummyEvent]  {
    extension (self: DummyEvent) def namespace: Namespace = Namespace(0)
    extension (self: DummyEvent) def hierarchy: Option[Hierarchy] = Hierarchy.Descendant(Key(1L), Key(2L)).some
    extension (self: DummyEvent) def props: Chunk[EventProperty] = Chunk(EventProperty("type", "DummyEvent"))
    extension (self: DummyEvent) def reference: Option[ReferenceKey] = None

  }

  def spec =
    suite("MemoryCQRSPersistenceSpec")(
      test("should save and retrieve events correctly by hierarchy : Descendant") {
        check(namespaceGen, keyGen, versionGen, keyGen, keyGen, discriminatorGen, dummyEventGen) {
          (ns, key, version, grandParentId, parentId, discriminator, event) =>

            given MetaInfo[DummyEvent]  {
              extension (self: DummyEvent) def namespace: Namespace = ns
              extension (self: DummyEvent)
                def hierarchy: Option[Hierarchy] = Hierarchy.Descendant(grandParentId, parentId).some
              extension (self: DummyEvent) def props: Chunk[EventProperty] = Chunk()
              extension (self: DummyEvent) def reference: Option[ReferenceKey] = None

            }

            for {
              persistence <- ZIO.service[CQRSPersistence]

              ch = Change(version = version, event)

              saveResult <- atomically(persistence.saveEvent(key, discriminator, ch, Catalog.Default))

              a = assert(saveResult)(equalTo(1L))

              events0 <- atomically(persistence.readEvents[DummyEvent](key, discriminator, Catalog.Default))
              b = assert(events0)(isNonEmpty)

              c <- (
                for {
                  events0 <- atomically(persistence.readEvents[DummyEvent](key, discriminator, Catalog.Default))
                  events1 <- atomically(
                    persistence.readEvents[DummyEvent](
                      discriminator,
                      query = PersistenceQuery.hierarchy(Hierarchy.Descendant(grandParentId, parentId)),
                      options = FetchOptions(),
                      catalog = Catalog.Default,
                    ),
                  )
                  events2 <- atomically(
                    persistence.readEvents[DummyEvent](
                      discriminator,
                      query = PersistenceQuery.hierarchy(Hierarchy.Descendant(Key(grandParentIdXXX), parentId)),
                      options = FetchOptions(),
                      catalog = Catalog.Default,
                    ),
                  )
                  events3 <- atomically(
                    persistence.readEvents[DummyEvent](
                      discriminator,
                      query = PersistenceQuery.hierarchy(Hierarchy.Descendant(grandParentId, Key(parentIdXXX))),
                      options = FetchOptions(),
                      catalog = Catalog.Default,
                    ),
                  )

                } yield assert(events0)(isNonEmpty) && assert(events1)(isNonEmpty) && assert(events2)(
                  isEmpty,
                ) && assert(
                  events3,
                )(isEmpty) && assert(
                  events1,
                )(hasSubset(events0))
              )

            } yield a && b && c
        }
      },
      test("should save and retrieve events correctly by hierarchy : Child") {
        check(namespaceGen, keyGen, versionGen, keyGen, keyGen, discriminatorGen, dummyEventGen) {
          (ns, key, version, grandParentId, parentId, discriminator, event) =>

            given MetaInfo[DummyEvent]  {
              extension (self: DummyEvent) def namespace: Namespace = ns
              extension (self: DummyEvent)
                def hierarchy: Option[Hierarchy] = Hierarchy.Descendant(grandParentId, parentId).some
              extension (self: DummyEvent) def props: Chunk[EventProperty] = Chunk(EventProperty("type", "DummyEvent"))
              extension (self: DummyEvent) def reference: Option[ReferenceKey] = None

            }

            for {
              persistence <- ZIO.service[CQRSPersistence]

              ch = Change(version = version, event)

              saveResult <- atomically(persistence.saveEvent(key, discriminator, ch, Catalog.Default))

              a = assert(saveResult)(equalTo(1L))

              events0 <- atomically(persistence.readEvents[DummyEvent](key, discriminator, Catalog.Default))
              b = assert(events0)(isNonEmpty)

              e <- (
                for {
                  events0 <- atomically(persistence.readEvents[DummyEvent](key, discriminator, Catalog.Default))
                  events1 <- atomically(
                    persistence.readEvents[DummyEvent](
                      discriminator,
                      query = PersistenceQuery.hierarchy(Hierarchy.Child(parentId)),
                      options = FetchOptions(),
                      catalog = Catalog.Default,
                    ),
                  )
                  events2 <- atomically(
                    persistence.readEvents[DummyEvent](
                      discriminator,
                      query = PersistenceQuery.hierarchy(Hierarchy.Child(Key(parentIdXXX))),
                      options = FetchOptions(),
                      catalog = Catalog.Default,
                    ),
                  )

                } yield assert(events0)(isNonEmpty) && assert(events1)(isNonEmpty) && assert(events2)(
                  isEmpty,
                ) && assert(
                  events1,
                )(hasSubset(events0))
              )

            } yield a && b && e
        }
      },
      test("should save and retrieve events correctly by hierarchy : GrandChild") {
        check(namespaceGen, keyGen, versionGen, keyGen, keyGen, discriminatorGen, dummyEventGen) {
          (ns, key, version, grandParentId, parentId, discriminator, event) =>

            given MetaInfo[DummyEvent]  {
              extension (self: DummyEvent) def namespace: Namespace = ns
              extension (self: DummyEvent)
                def hierarchy: Option[Hierarchy] = Hierarchy.Descendant(grandParentId, parentId).some
              extension (self: DummyEvent) def props: Chunk[EventProperty] = Chunk(EventProperty("type", "DummyEvent"))
              extension (self: DummyEvent) def reference: Option[ReferenceKey] = None

            }

            for {
              persistence <- ZIO.service[CQRSPersistence]

              ch = Change(version = version, event)

              saveResult <- atomically(persistence.saveEvent(key, discriminator, ch, Catalog.Default))

              a = assert(saveResult)(equalTo(1L))

              events0 <- atomically(persistence.readEvents[DummyEvent](key, discriminator, Catalog.Default))
              b = assert(events0)(isNonEmpty)

              d <- (
                for {
                  events0 <- atomically(persistence.readEvents[DummyEvent](key, discriminator, Catalog.Default))
                  events1 <- atomically(
                    persistence.readEvents[DummyEvent](
                      discriminator,
                      query = PersistenceQuery.hierarchy(Hierarchy.GrandChild(grandParentId)),
                      options = FetchOptions(),
                      catalog = Catalog.Default,
                    ),
                  )
                  events2 <- atomically(
                    persistence.readEvents[DummyEvent](
                      discriminator,
                      query = PersistenceQuery.hierarchy(Hierarchy.GrandChild(Key(grandParentIdXXX))),
                      options = FetchOptions(),
                      catalog = Catalog.Default,
                    ),
                  )

                } yield assert(events0)(isNonEmpty) && assert(events1)(isNonEmpty) && assert(events2)(
                  isEmpty,
                ) && assert(
                  events1,
                )(hasSubset(events0))
              )

            } yield a && b && d
        }
      },
      test("should save and retrieve events correctly by props") {
        check(keyGen, versionGen, discriminatorGen) { (key, version, discriminator) =>
          for {
            persistence <- ZIO.service[CQRSPersistence]

            event = Change(version = version, DummyEvent("test"))

            saveResult <- atomically(persistence.saveEvent(key, discriminator, event, Catalog.Default))

            a = assert(saveResult)(equalTo(1L))

            events0 <- atomically(persistence.readEvents[DummyEvent](key, discriminator, Catalog.Default))
            events1 <- atomically(
              persistence.readEvents[DummyEvent](
                discriminator,
                query = PersistenceQuery.props(EventProperty("type", "DummyEvent")),
                options = FetchOptions(),
                catalog = Catalog.Default,
              ),
            )
            events2 <- atomically(
              persistence.readEvents[DummyEvent](
                discriminator,
                query = PersistenceQuery.props(EventProperty("type", "DummyEventXXX")),
                options = FetchOptions(),
                catalog = Catalog.Default,
              ),
            )
            b = assert(events0)(isNonEmpty) && assert(events1)(isNonEmpty) && assert(events2)(isEmpty)

          } yield a && b
        }
      },
      test("should save and retrieve events correctly") {
        check(keyGen, versionGen, discriminatorGen) { (key, version, discriminator) =>
          for {
            persistence <- ZIO.service[CQRSPersistence]

            event = Change(version = version, DummyEvent("test"))

            saveResult <- atomically(persistence.saveEvent(key, discriminator, event, Catalog.Default))

            a = assert(saveResult)(equalTo(1L))

            events <- atomically(persistence.readEvents[DummyEvent](key, discriminator, Catalog.Default))
            b = assert(events)(isNonEmpty) && assert(events)(equalTo(Chunk(event)))

          } yield a && b
        }
      },
      test("should save and retrieve an event correctly") {
        check(keyGen, versionGen, discriminatorGen) { (key, version, discriminator) =>
          for {
            persistence <- ZIO.service[CQRSPersistence]
            event = Change(version = version, DummyEvent("test"))

            saveResult <- atomically(persistence.saveEvent(key, discriminator, event, Catalog.Default))
            result <- atomically(persistence.readEvent[DummyEvent](version = event.version, Catalog.Default))
          } yield assert(result)(isSome(equalTo(event)))
        }
      },
      test("should save and retrieve events by namespace correctly") {
        check(keyGen, versionGen, discriminatorGen) { (key, version, discriminator) =>
          for {
            persistence <- ZIO.service[CQRSPersistence]

            event = Change(version = version, DummyEvent("test"))

            saveResult <- atomically(persistence.saveEvent(key, discriminator, event, Catalog.Default))

            a = assert(saveResult)(equalTo(1L))

            events0 <- atomically(
              persistence
                .readEvents[DummyEvent](
                  discriminator,
                  query = PersistenceQuery.ns(Namespace(0)),
                  options = FetchOptions(),
                  catalog = Catalog.Default,
                ),
            )
            b = assert(events0)(isNonEmpty)

            events1 <- atomically(
              persistence
                .readEvents[DummyEvent](
                  discriminator,
                  query = PersistenceQuery.ns(Namespace(1)),
                  options = FetchOptions(),
                  catalog = Catalog.Default,
                ),
            )
            c = assert(events1)(isEmpty)

          } yield a && b
        }
      },
      test("should retrieve events in sorted order") {
        check(keyGen, Gen.listOfN(100)(versionGen), discriminatorGen) { (key, versions, discriminator) =>
          for {
            persistence <- ZIO.service[CQRSPersistence]

            events = versions.zipWithIndex.map { case (version, index) =>
              Change(version = version, payload = DummyEvent(s"${index + 1}"))
            }

            _ <- atomically {
              ZIO.foreachDiscard(events) { e =>
                persistence.saveEvent(key, discriminator, e, Catalog.Default)
              }
            }

            es <- atomically(persistence.readEvents[DummyEvent](key, discriminator, Catalog.Default))

            a = assert(es)(equalTo(es.sorted)) && assert(es)(equalTo(events.sorted))

          } yield a
        }
      },
      test("should retrieve events from given version") {
        check(keyGen, Gen.listOfN(100)(versionGen), Gen.listOfN(100)(versionGen), discriminatorGen) {
          (key, versions, moreVersions, discriminator) =>
            for {
              persistence <- ZIO.service[CQRSPersistence]

              events = versions.zipWithIndex.map { case (version, i) =>
                Change(version = version, payload = DummyEvent(s"${i + 1}"))
              }

              _ <- atomically {
                ZIO.foreachDiscard(events) { e =>
                  persistence.saveEvent(key, discriminator, e, Catalog.Default)
                }
              }

              es <- atomically(persistence.readEvents[DummyEvent](key, discriminator, Catalog.Default))

              a = assert((es))(equalTo((events.sorted)))

              max = es.maxBy(_.version)

              es1 <- atomically(
                persistence.readEvents[DummyEvent](key, discriminator, snapshotVersion = max.version, Catalog.Default),
              )

              b = assert(es1)(isEmpty)

              events1 = moreVersions.zipWithIndex.map { case (version, i) =>
                Change(version = version, payload = DummyEvent(s"${i + 1}"))
              }

              _ <- atomically {
                ZIO.foreachDiscard(events1) { e =>
                  persistence.saveEvent(key, discriminator, e, Catalog.Default)
                }
              }

              es2 <- atomically(
                persistence.readEvents[DummyEvent](key, discriminator, snapshotVersion = max.version, Catalog.Default),
              )

              c = assert(es2)(equalTo(events1.sorted))

              max1 = es2.maxBy(_.version)
              es3 <- atomically(
                persistence.readEvents[DummyEvent](key, discriminator, snapshotVersion = max1.version, Catalog.Default),
              )
              d = assert(es3)(isEmpty)
            } yield a && b && c && d
        }
      },
      test("should handle snapshot storage correctly") {
        check(keyGen, versionGen) { (key, version) =>
          for {
            persistence <- ZIO.service[CQRSPersistence]

            saveSnapshotResult <- atomically(persistence.saveSnapshot(key, version))
            a = assert(saveSnapshotResult)(equalTo(1L))

            snapshot <- atomically(persistence.readSnapshot(key))
            b = assert(snapshot)(isSome(equalTo(version)))

          } yield a && b
        }
      },
    ).provideSomeLayerShared(
      ZLayer.make[Tracing & ZConnectionPool & CQRSPersistence](
        ZConnectionMock.pool(),
        tracingMockLayer(),
        zio.telemetry.opentelemetry.OpenTelemetry.contextZIO,
        MemoryCQRSPersistence.live(),
      ),
    ) @@ TestAspect.withLiveClock

  val dummyEventGen: Gen[Any, DummyEvent] =
    Gen.alphaNumericStringBounded(4, 36).map(DummyEvent(_))

  val namespaceGen: Gen[Any, Namespace] =
    Gen.int.map(Namespace(_))

  val discriminatorGen: Gen[Any, Discriminator] =
    Gen.alphaNumericStringBounded(5, 5).map(Discriminator(_))

  val keyGen: Gen[Any, Key] = Gen.fromZIO(Key.gen.orDie)
  val versionGen: Gen[Any, Version] = Gen.fromZIO(Version.gen.orDie)

  val hierarchyGen: Gen[Any, Hierarchy] = Gen.oneOf(
    keyGen map { parentId => Hierarchy.Child(parentId) },
    keyGen map { grandParentId => Hierarchy.GrandChild(grandParentId) },
    (keyGen <*> keyGen) map { case (grandParentId, parentId) => Hierarchy.Descendant(grandParentId, parentId) },
  )

  val namespacesGen: Gen[Any, NonEmptyList[Namespace]] =
    Gen
      .setOfBounded(1, 8)(Gen.int)
      .map(s =>
        NonEmptyList.fromIterableOption(s) match {
          case None => throw IllegalArgumentException("empty")
          case Some(nes) => nes.map(Namespace.apply)
        },
      )

  val eventPropertiesGen: Gen[Any, NonEmptyList[EventProperty]] =
    Gen
      .setOfBounded(1, 8)(
        (Gen.alphaNumericStringBounded(3, 20) <*> Gen.alphaNumericStringBounded(128, 512)) `map` EventProperty.apply,
      )
      .map(s => NonEmptyList.fromIterableOption(s).getOrElse(throw IllegalArgumentException("empty")))
}
