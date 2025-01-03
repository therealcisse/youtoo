package com.youtoo
package ingestion
package model

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.*
import zio.*
import com.youtoo.cqrs.*

object IngestionCommandHandlerFuzzSpec extends ZIOSpecDefault {

  def spec = suite("IngestionCommandHandlerFuzzSpec")(
    test("Fuzz test IngestionCommandHandler with random commands") {
      check(Gen.listOf1(ingestionCommandGen)) { commands =>
        ZIO.foldLeft(commands)(assert(true)(isTrue)) { (s, cmd) =>
          ZIO.attempt(CmdHandler.applyCmd(cmd)).either.map {
            case Left(_) =>
              assert(false)(isTrue) // Fail the test if an exception is thrown
            case Right(events) =>
              s && assert(events.toList)(isNonEmpty)
          }
        }
      }
    },
  )
}
