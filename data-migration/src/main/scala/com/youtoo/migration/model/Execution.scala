package com.youtoo
package migration
package model

import cats.Order

import cats.implicits.*

import zio.*

import zio.prelude.*
import zio.schema.*

enum Execution {
  case Failed(processing: Execution.Processing, timestamp: Timestamp)
  case Stopped(processing: Execution.Processing, timestamp: Timestamp)
  case Finished(processing: Execution.Processing, timestamp: Timestamp)
  case Processing(id: Execution.Id, stats: Stats, timestamp: Timestamp)

  def keys: Set[Key] = this match {
    case processing: Execution.Processing => processing.stats.processed
    case stopped: Execution.Stopped => stopped.processing.stats.processed
    case finished: Execution.Finished => finished.processing.stats.processed
    case failed: Execution.Failed => failed.processing.stats.processed
  }

  def totalProcessed: Long = this match {
    case processing: Execution.Processing => processing.stats.processed.size
    case stopped: Execution.Stopped => stopped.processing.stats.processed.size
    case finished: Execution.Finished => finished.processing.stats.processed.size
    case failed: Execution.Failed => failed.processing.stats.processed.size
  }

  def startTime: Timestamp = this match {
    case processing: Execution.Processing => processing.timestamp
    case stopped: Execution.Stopped => stopped.timestamp
    case finished: Execution.Finished => finished.timestamp
    case failed: Execution.Failed => failed.timestamp
  }

  def endTime: Option[Timestamp] = this match {
    case _: Execution.Processing => None
    case stopped: Execution.Stopped => Some(stopped.timestamp)
    case finished: Execution.Finished => Some(finished.timestamp)
    case failed: Execution.Failed => Some(failed.timestamp)
  }

  def key: Execution.Id = this match {
    case processing: Execution.Processing => processing.id
    case stopped: Execution.Stopped => stopped.processing.id
    case finished: Execution.Finished => finished.processing.id
    case failed: Execution.Failed => failed.processing.id

  }

}

object Execution {
  given Schema[Execution] = DeriveSchema.gen

  type Id = Id.Type

  object Id extends Newtype[Key] {
    import zio.schema.*

    def gen: Task[Id] = Key.gen.map(Id.wrap)

    extension (a: Id) inline def asKey: Key = Id.unwrap(a)

    given Schema[Id] = derive

    given Order[Id] = Order.by(_.asKey)

  }

}
