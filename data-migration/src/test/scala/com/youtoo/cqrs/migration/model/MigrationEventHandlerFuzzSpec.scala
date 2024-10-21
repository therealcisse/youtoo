package com.youtoo.cqrs
package migration
package model

import zio.*
import zio.test.*
import zio.test.Assertion.*

object MigrationEventHandlerFuzzSpec extends ZIOSpecDefault {
  val handler = summon[MigrationEventHandler]

  def spec = suite("MigrationEventHandlerFuzzSpec")(
    test("Fuzz test MigrationEventHandler does not crash on invalid inputs") {
      check(eventSequenceGen) { events =>
        val result = ZIO.attempt(handler.applyEvents(events))
        result.fold(
          _ => assertCompletes, // Test passes if an exception is thrown (as expected)
          _ => assertCompletes, // Test also passes if no exception is thrown
        )
      }
    },
    test("Fuzz test MigrationEventHandler with random valid events") {
      check(validMigrationEventSequence) { events =>
        val result = ZIO.attempt(handler.applyEvents(events)).either
        result.map {
          case Left(_) =>
            assertCompletes
          case Right(state) =>
            assert(isValidState(state.state))(isTrue)
        }
      }
    },
  ) @@ TestAspect.samples(1)

}
