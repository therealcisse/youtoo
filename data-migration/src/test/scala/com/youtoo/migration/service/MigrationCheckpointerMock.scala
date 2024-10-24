package com.youtoo
package migration
package service

import com.youtoo.migration.model.*

import zio.mock.*

import zio.*

object MigrationCheckpointerMock extends Mock[MigrationCheckpointer] {

  object Save extends Effect[Migration, Throwable, Unit]

  val compose: URLayer[Proxy, MigrationCheckpointer] =
    ZLayer {
      for {
        proxy <- ZIO.service[Proxy]
      } yield new MigrationCheckpointer {
        def save(o: Migration): Task[Unit] = proxy(Save, o)
      }
    }

}
