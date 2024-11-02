package com.youtoo
package cqrs
package service
package postgres

import cats.implicits.*

import com.youtoo.cqrs.domain.*

import zio.schema.codec.*

import zio.prelude.*

import com.youtoo.cqrs.Codecs.given
import java.nio.charset.StandardCharsets

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
          discriminator: Discriminator,
          ns: Option[NonEmptyList[Namespace]],
          hierarchy: Option[Hierarchy],
          props: Option[NonEmptyList[EventProperty]],
        ): RIO[ZConnection, Chunk[Change[Event]]] =
          Queries.READ_EVENTS(discriminator, ns, hierarchy, props).selectAll

        def readEvents[Event: BinaryCodec: Tag](
          discriminator: Discriminator,
          snapshotVersion: Version,
          ns: Option[NonEmptyList[Namespace]],
          hierarchy: Option[Hierarchy],
          props: Option[NonEmptyList[EventProperty]],
        ): RIO[ZConnection, Chunk[Change[Event]]] =
          Queries.READ_EVENTS(discriminator, snapshotVersion, ns, hierarchy, props).selectAll

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
      discriminator: Discriminator,
      ns: Option[NonEmptyList[Namespace]],
      hierarchy: Option[Hierarchy],
      props: Option[NonEmptyList[EventProperty]],
    ): Query[Change[Event]] =

      @scala.annotation.tailrec
      def propsQuery(
        props: Option[NonEmptyList[EventProperty]],
        q: SqlFragment = SqlFragment.empty,
      ): SqlFragment = props match {
        case None => q
        case Some(NonEmptyList.Single(EventProperty(key, value))) =>
          q ++ s""" AND props->>'$key' = """ ++ sql"""$value"""
        case Some(NonEmptyList.Cons(EventProperty(key, value), ns)) =>
          propsQuery(ns.some, q ++ s""" AND props->>'$key' = """ ++ sql"""$value""")
      }

      val nsQuery = ns.fold(SqlFragment.empty) {
        case NonEmptyList.Single(n) => sql" AND namespace = $n"
        case NonEmptyList.Cons(n, ns) => sql" AND namespace in (${n :: ns.toList})"
      }

      val hierarchyQuery = hierarchy.fold(SqlFragment.empty) {
        case Hierarchy.Child(parentId) => sql""" AND parent_id = $parentId"""
        case Hierarchy.GrandChild(grandParentId) => sql""" AND grand_parent_id = $grandParentId"""
        case Hierarchy.Descendant(grandParentId, parentId) =>
          sql""" AND parent_id = $parentId AND grand_parent_id = $grandParentId"""

      }

      (sql"""
      SELECT
        version,
        payload
      FROM events
      WHERE discriminator = $discriminator""" ++ nsQuery ++ hierarchyQuery ++ propsQuery(props) ++
        sql""" ORDER BY version ASC""").query[(Version, Event)].map(Change.apply)

    def READ_EVENTS[Event: BinaryCodec](
      discriminator: Discriminator,
      snapshotVersion: Version,
      ns: Option[NonEmptyList[Namespace]],
      hierarchy: Option[Hierarchy],
      props: Option[NonEmptyList[EventProperty]],
    ): Query[Change[Event]] =
      @scala.annotation.tailrec
      def propsQuery(
        props: Option[NonEmptyList[EventProperty]],
        q: SqlFragment = SqlFragment.empty,
      ): SqlFragment = props match {
        case None => q
        case Some(NonEmptyList.Single(EventProperty(key, value))) =>
          q ++ s""" AND props->>'$key' = """ ++ sql"""$value"""
        case Some(NonEmptyList.Cons(EventProperty(key, value), ns)) =>
          propsQuery(ns.some, q ++ s""" AND props->>'$key' = """ ++ sql"""$value""")
      }

      val nsQuery = ns.fold(SqlFragment.empty) {
        case NonEmptyList.Single(n) => sql" AND namespace = $n"
        case NonEmptyList.Cons(n, ns) => sql" AND namespace in (${n :: ns.toList})"
      }

      val hierarchyQuery = hierarchy.fold(SqlFragment.empty) {
        case Hierarchy.Child(parentId) => sql""" AND parent_id = $parentId"""
        case Hierarchy.GrandChild(grandParentId) => sql""" AND grand_parent_id = $grandParentId"""
        case Hierarchy.Descendant(grandParentId, parentId) =>
          sql""" AND parent_id = $parentId AND grand_parent_id = $grandParentId"""

      }

      (sql"""
      SELECT
        version,
        payload
      FROM events
      WHERE discriminator = $discriminator
        AND version > $snapshotVersion""" ++ nsQuery ++ hierarchyQuery ++ propsQuery(props) ++
        sql""" ORDER BY version ASC""").query[(Version, Event)].map(Change.apply)

    def SAVE_EVENT[Event: BinaryCodec: MetaInfo](
      id: Key,
      discriminator: Discriminator,
      event: Change[Event],
    ): SqlFragment =
      val payload = java.util.Base64.getEncoder.encodeToString(summon[BinaryCodec[Event]].encode(event.payload).toArray)

      val props = String(toJson(event.payload.props).toArray, StandardCharsets.UTF_8.name)

      event.payload.hierarchy match {
        case None =>
          sql"""
          INSERT INTO events (version, aggregate_id, discriminator, namespace, props, payload)
          VALUES (
            ${event.version},
            ${id},
            ${discriminator},
            ${event.payload.namespace},
            (
              SELECT jsonb_object_agg(elem->>'key', elem->>'value')
              FROM jsonb_array_elements($props :: jsonb) as elem
              ),
            decode(${payload}, 'base64')
          )
          """

        case Some(Hierarchy.GrandChild(grandParentId)) =>
          sql"""
          INSERT INTO events (version, aggregate_id, discriminator, namespace, grand_parent_id, props, payload)
          VALUES (
            ${event.version},
            ${id},
            ${discriminator},
            ${event.payload.namespace},
            $grandParentId,
            (
              SELECT jsonb_object_agg(elem->>'key', elem->>'value')
              FROM jsonb_array_elements($props :: jsonb) as elem
              ),
            decode(${payload}, 'base64')
          )
          """

        case Some(Hierarchy.Child(parentId)) =>
          sql"""
          INSERT INTO events (version, aggregate_id, discriminator, namespace, parent_id, props, payload)
          VALUES (
            ${event.version},
            ${id},
            ${discriminator},
            ${event.payload.namespace},
            $parentId,
            (
              SELECT jsonb_object_agg(elem->>'key', elem->>'value')
              FROM jsonb_array_elements($props :: jsonb) as elem
              ),
            decode(${payload}, 'base64')
          )
          """

        case Some(Hierarchy.Descendant(grandParentId, parentId)) =>
          sql"""
          INSERT INTO events (version, aggregate_id, discriminator, namespace, parent_id, grand_parent_id, props, payload)
          VALUES (
            ${event.version},
            ${id},
            ${discriminator},
            ${event.payload.namespace},
            $parentId,
            $grandParentId,
            (
              SELECT jsonb_object_agg(elem->>'key', elem->>'value')
              FROM jsonb_array_elements($props :: jsonb) as elem
              ),
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
