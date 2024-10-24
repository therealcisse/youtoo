package com.youtoo
package cqrs
package service
package postgres

import com.youtoo.cqrs.config.*

import zio.*

trait FlywayMigration {
  def run(config: DatabaseConfig): Task[Unit]

}

object FlywayMigration {
  inline def run(config: DatabaseConfig): RIO[FlywayMigration, Unit] =
    ZIO.serviceWithZIO(_.run(config))

  def live(): ZLayer[Any, Throwable, FlywayMigration] =
    ZLayer.succeed {
      new FlywayMigration {
        def run(config: DatabaseConfig): Task[Unit] =
          runMigration(config).tapErrorCause { e =>
            ZIO.logErrorCause("Migration failed", e)
          }

      }
    }

  def runMigration(config: DatabaseConfig): Task[Unit] =
    ZIO.attemptBlocking {
      import org.flywaydb.core.Flyway
      import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

      val hikariConfig = HikariConfig()

      hikariConfig.setDriverClassName(config.driverClassName)
      hikariConfig.setJdbcUrl(config.jdbcUrl)
      hikariConfig.setUsername(config.username)
      hikariConfig.setPassword(config.password)

      val dataSource = HikariDataSource(hikariConfig)

      val flyway = Flyway
        .configure()
        .dataSource(dataSource)
        .locations(config.migrations)
        .outOfOrder(true)
        .load()

      flyway.migrate()
    }

}
