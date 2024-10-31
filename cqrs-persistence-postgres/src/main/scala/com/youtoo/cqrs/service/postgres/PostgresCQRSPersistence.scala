package com.youtoo
package cqrs
package service
package postgres

import com.youtoo.cqrs.domain.*

import zio.schema.codec.*

import com.youtoo.cqrs.Codecs.given

trait PostgresCQRSPersistence extends CQRSPersistence {}

object PostgresCQRSPersistence {
  import zio.*
  import zio.jdbc.*
  import zio.schema.*

  def live(): ZLayer[Any, Throwable, CQRSPersistence] =
    ZLayer.succeed {
      new CQRSPersistence {
        def readEvents[Event: BinaryCodec: Tag](
          id: Key,
          discriminator: Discriminator,
        ): RIO[ZConnection, Chunk[Change[Event]]] =
          Queries.READ_EVENTS(id, discriminator).selectAll

        def readEvents[Event: BinaryCodec: Tag](
          id: Key,
          discriminator: Discriminator,
          snapshotVersion: Version,
        ): RIO[ZConnection, Chunk[Change[Event]]] =
          Queries.READ_EVENTS(id, discriminator, snapshotVersion).selectAll

        def readEvents[Event: BinaryCodec: Tag](
          id: Key,
          discriminator: Discriminator,
          ns: Option[NonEmptyChunk[Namespace]],
          hierarchy: Option[Hierarchy],
        ): RIO[ZConnection, Chunk[Change[Event]]] =
          Queries.READ_EVENTS(id, discriminator, ns, hierarchy).selectAll

        def readEvents[Event: BinaryCodec: Tag](
          id: Key,
          discriminator: Discriminator,
          snapshotVersion: Version,
          ns: Option[NonEmptyChunk[Namespace]],
          hierarchy: Option[Hierarchy],
        ): RIO[ZConnection, Chunk[Change[Event]]] =
          Queries.READ_EVENTS(id, discriminator, snapshotVersion, ns, hierarchy).selectAll

        def saveEvent[Event: BinaryCodec: MetaInfo: Tag](
          id: Key,
          discriminator: Discriminator,
          event: Change[Event],
        ): RIO[ZConnection, Long] =
          Queries.SAVE_EVENT(id, discriminator, event).insert

        def readSnapshot(id: Key): RIO[ZConnection, Option[Version]] =
          Queries.READ_SNAPSHOT(id).selectOne

        def saveSnapshot(id: Key, version: Version): RIO[ZConnection, Long] =
          Queries.SAVE_SNAPSHOT(id, version).insert
      }

    }

  object Queries extends JdbcCodecs {
    given [E: BinaryCodec]: JdbcDecoder[E] = byteArrayDecoder[E]

    def READ_EVENTS[Event: BinaryCodec](id: Key, discriminator: Discriminator): Query[Change[Event]] =
      sql"""
      SELECT
        version,
        payload
      FROM events
      WHERE aggregate_id = $id AND discriminator = $discriminator
      ORDER BY version ASC
      """.query[(Version, Event)].map(Change.apply)

    def READ_EVENTS[Event: BinaryCodec](
      id: Key,
      discriminator: Discriminator,
      snapshotVersion: Version,
    ): Query[Change[Event]] =
      sql"""
      SELECT
        version,
        payload
      FROM events
      WHERE aggregate_id = $id AND discriminator = $discriminator AND version > $snapshotVersion
      ORDER BY version ASC
      """.query[(Version, Event)].map(Change.apply)

    def READ_EVENTS[Event: BinaryCodec](
      id: Key,
      discriminator: Discriminator,
      ns: Option[NonEmptyChunk[Namespace]],
      hierarchy: Option[Hierarchy],
    ): Query[Change[Event]] =

      val nsQuery = ns.fold(SqlFragment.empty)(ns => sql" AND namespace in (${ns.toChunk})")
      val hierarchyQuery = hierarchy.fold(SqlFragment.empty) {
        case Hierarchy.Child(parentId) => sql""" AND parent_id = $parentId"""
        case Hierarchy.Descendant(grandParentId, parentId) =>
          sql""" AND parent_id = $parentId AND grand_parent_id = $grandParentId"""

      }

      (sql"""
      SELECT
        version,
        payload
      FROM events
      WHERE aggregate_id = $id
        AND discriminator = $discriminator""" ++ nsQuery ++ hierarchyQuery ++
        sql""" ORDER BY version ASC""").query[(Version, Event)].map(Change.apply)

    def READ_EVENTS[Event: BinaryCodec](
      id: Key,
      discriminator: Discriminator,
      snapshotVersion: Version,
      ns: Option[NonEmptyChunk[Namespace]],
      hierarchy: Option[Hierarchy],
    ): Query[Change[Event]] =
      val nsQuery = ns.fold(SqlFragment.empty)(ns => sql" AND namespace in (${ns.toChunk})")
      val hierarchyQuery = hierarchy.fold(SqlFragment.empty) {
        case Hierarchy.Child(parentId) => sql""" AND parent_id = $parentId"""
        case Hierarchy.Descendant(grandParentId, parentId) =>
          sql""" AND parent_id = $parentId AND grand_parent_id = $grandParentId"""

      }

      (sql"""
      SELECT
        version,
        payload
      FROM events
      WHERE aggregate_id = $id
        AND discriminator = $discriminator
        AND version > $snapshotVersion""" ++ nsQuery ++ hierarchyQuery ++
        sql""" ORDER BY version ASC""").query[(Version, Event)].map(Change.apply)

    def SAVE_EVENT[Event: BinaryCodec: MetaInfo](
      id: Key,
      discriminator: Discriminator,
      event: Change[Event],
    ): SqlFragment =
      val payload = java.util.Base64.getEncoder.encodeToString(summon[BinaryCodec[Event]].encode(event.payload).toArray)

      event.payload.hierarchy match {
        case None =>
          sql"""
          INSERT INTO events (version, aggregate_id, discriminator, namespace, payload)
          VALUES (
            ${event.version},
            ${id},
            ${discriminator},
            ${event.payload.namespace},
            decode(${payload}, 'base64')
          )
          """

        case Some(Hierarchy.Child(parentId)) =>
          sql"""
          INSERT INTO events (version, aggregate_id, discriminator, namespace, parent_id, payload)
          VALUES (
            ${event.version},
            ${id},
            ${discriminator},
            ${event.payload.namespace},
            $parentId,
            decode(${payload}, 'base64')
          )
          """

        case Some(Hierarchy.Descendant(grandParentId, parentId)) =>
          sql"""
          INSERT INTO events (version, aggregate_id, discriminator, namespace, parent_id, grand_parent_id, payload)
          VALUES (
            ${event.version},
            ${id},
            ${discriminator},
            $parentId,
            $grandParentId,
            decode(${payload}, 'base64')
          )
          """

      }

    def READ_SNAPSHOT(id: Key): Query[Version] =
      sql"""
      SELECT version
      FROM snapshots
      WHERE aggregate_id = $id
      """.query[Version]

    def SAVE_SNAPSHOT(id: Key, version: Version): SqlFragment =
      sql"""
      INSERT INTO snapshots (aggregate_id, version)
      VALUES ($id, $version)
      ON CONFLICT (aggregate_id) DO UPDATE SET version = $version
      """
  }

}
