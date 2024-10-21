package com.youtoo.cqrs
package service

import com.youtoo.cqrs.migration.service.*
import com.youtoo.cqrs.migration.model.*

import zio.mock.*

import zio.*

object MigrationProviderMock extends Mock[MigrationProvider] {

  object Load extends Effect[Key, Throwable, Option[Migration]]

  val compose: URLayer[Proxy, MigrationProvider] =
    ZLayer {
      for {
        proxy <- ZIO.service[Proxy]
      } yield new MigrationProvider {
        def load(id: Key): Task[Option[Migration]] = proxy(Load, id)
      }
    }

}
