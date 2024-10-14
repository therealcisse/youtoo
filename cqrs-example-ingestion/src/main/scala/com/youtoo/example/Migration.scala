package com.youtoo.cqrs
package example

import com.youtoo.cqrs.example.config.*

import zio.*

trait Migration {
  def run: Task[Unit]

}

object Migration {
  inline def run: RIO[Migration, Unit] =
    ZIO.serviceWithZIO(_.run)

  def live(): ZLayer[Any, Throwable, Migration] =
    ZLayer.succeed {
      new Migration {
        def run: Task[Unit] =
          for {
            config <- ZIO.config[DatabaseConfig]

            _ <- runMigration(config)
          } yield ()

      }
    }

  def runMigration(config: DatabaseConfig): Task[Unit] =
    ZIO.attemptBlocking {
      import org.flywaydb.core.Flyway
      import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

      val hikariConfig = HikariConfig()

      hikariConfig.setDriverClassName(config.driverName)
      hikariConfig.setJdbcUrl(config.databaseUrl)
      hikariConfig.setUsername(config.username)
      hikariConfig.setPassword(config.password)

      val dataSource = HikariDataSource(hikariConfig)

      val flyway = Flyway
        .configure()
        .dataSource(dataSource)
        .locations(config.migrations)
        .load()

      flyway.migrate()
    }

}
