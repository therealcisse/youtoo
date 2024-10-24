package com.youtoo
package ingestion

import zio.*
import zio.test.*
import zio.prelude.*

import com.youtoo.cqrs.*
import com.youtoo.cqrs.domain.*

import com.youtoo.ingestion.model.*

import com.youtoo.cqrs.Codecs.given

given keyGen: Gen[Any, Key] = Gen.fromZIO(Key.gen.orDie)
given ingestionIdGen: Gen[Any, Ingestion.Id] = Gen.fromZIO(Ingestion.Id.gen.orDie)
given timestampGen: Gen[Any, Timestamp] = Gen.fromZIO(Timestamp.now.orDie)

given ingestionGen: Gen[Any, Ingestion] =
  (ingestionIdGen <*> IngestionStatusGenerators.genStatus <*> timestampGen) map { case (id, status, timestamp) =>
    Ingestion(id, status = status, timestamp)
  }

val startIngestionGen: Gen[Any, IngestionCommand.StartIngestion] =
  for {
    id <- ingestionIdGen
    timestamp <- timestampGen
  } yield IngestionCommand.StartIngestion(id, timestamp)

val setFilesGen: Gen[Any, IngestionCommand.SetFiles] =
  Gen
    .setOfBounded(1, 12)(Gen.alphaNumericString)
    .map(s =>
      NonEmptySet.fromIterableOption(s) match {
        case None => throw IllegalArgumentException("empty")
        case Some(nes) => IngestionCommand.SetFiles(nes)
      },
    )

val fileProcessedGen: Gen[Any, IngestionCommand.FileProcessed] =
  Gen.alphaNumericString.map(IngestionCommand.FileProcessed.apply)

val fileFailedGen: Gen[Any, IngestionCommand.FileFailed] =
  Gen.alphaNumericString.map(IngestionCommand.FileFailed.apply)

val stopIngestionGen: Gen[Any, IngestionCommand.StopIngestion] =
  timestampGen.map(IngestionCommand.StopIngestion.apply)

val ingestionCommandGen: Gen[Any, IngestionCommand] =
  Gen.oneOf(
    startIngestionGen,
    setFilesGen,
    fileProcessedGen,
    fileFailedGen,
    stopIngestionGen,
  )

given versionGen: Gen[Any, Version] = Gen.fromZIO(Version.gen.orDie)

val ingestionStartedGen: Gen[Any, IngestionEvent.IngestionStarted] =
  for {
    id <- ingestionIdGen
    timestamp <- timestampGen
  } yield IngestionEvent.IngestionStarted(id, timestamp)

val ingestionFilesResolvedGen: Gen[Any, IngestionEvent.IngestionFilesResolved] =
  Gen
    .setOfBounded(3, 8)(Gen.alphaNumericString)
    .map(s =>
      NonEmptySet.fromIterableOption(s) match {
        case None => throw IllegalArgumentException("empty")
        case Some(nes) => IngestionEvent.IngestionFilesResolved(nes)
      },
    )

val ingestionFileProcessedGen: Gen[Any, IngestionEvent.IngestionFileProcessed] =
  Gen.alphaNumericString.map(IngestionEvent.IngestionFileProcessed.apply)

val ingestionFileFailedGen: Gen[Any, IngestionEvent.IngestionFileFailed] =
  Gen.alphaNumericString.map(IngestionEvent.IngestionFileFailed.apply)

val ingestionCompletedGen: Gen[Any, IngestionEvent.IngestionCompleted] =
  timestampGen.map(IngestionEvent.IngestionCompleted.apply)

val ingestionEventGen: Gen[Any, IngestionEvent] =
  Gen.oneOf(
    ingestionStartedGen,
    ingestionFilesResolvedGen,
    ingestionFileProcessedGen,
    ingestionFileFailedGen,
    ingestionCompletedGen,
  )

val changeEventGen: Gen[Any, Change[IngestionEvent]] =
  (versionGen <*> ingestionEventGen).map(Change.apply)

def isValidState(status: Ingestion.Status): Boolean = status match {
  case Ingestion.Status.Completed(_) => true
  case Ingestion.Status.Failed(_, _) => true
  case Ingestion.Status.Processing(remaining, processing, processed, failed) =>
    remaining.union(processed).union(failed).union(processing).nonEmpty
  case Ingestion.Status.Resolved(_) => true
  case Ingestion.Status.Initial() =>
    true
  case Ingestion.Status.Stopped(processing, _) =>
    isValidState(processing)
}

val commandSequenceGen: Gen[Any, List[IngestionCommand]] =
  Gen.listOf(ingestionCommandGen)

val eventSequenceGen: Gen[Any, NonEmptyList[Change[IngestionEvent]]] =
  for {
    events <- Gen.listOf(ingestionEventGen)
    if events.nonEmpty
    changes <- Gen.fromZIO {
      ZIO.foreach(events) { case event =>
        for {
          v <- Version.gen.orDie
        } yield Change(v, event)
      }
    }
  } yield NonEmptyList.fromIterable(changes.head, changes.tail)

val validEventSequenceGen: Gen[Any, NonEmptyList[Change[IngestionEvent]]] =
  for {
    id <- ingestionIdGen
    version <- versionGen
    timestamp <- timestampGen
    startEvent = Change(version, IngestionEvent.IngestionStarted(id, timestamp))
    otherEvents <- Gen.listOf(
      Gen.oneOf(
        ingestionFilesResolvedGen,
        ingestionFileProcessedGen,
        ingestionFileFailedGen,
        ingestionCompletedGen,
      ),
    )
    changes <- Gen.fromZIO {

      ZIO.foreach(otherEvents) { case event =>
        for {
          v <- Version.gen.orDie
        } yield Change(v, event)
      }
    }
  } yield NonEmptyList.fromIterable(startEvent, changes)

object IngestionStatusGenerators {

  val genString: Gen[Any, String] = Gen.alphaNumericStringBounded(8, 32)

  val genSetString: Gen[Any, Set[String]] = Gen.setOf(genString)

  val genNonEmptySetString: Gen[Any, NonEmptySet[String]] =
    Gen.setOfBounded(1, 36)(genString).map(chunk => NonEmptySet.fromIterableOption(chunk).get)

  val genInitial: Gen[Any, Ingestion.Status.Initial] = Gen.const(Ingestion.Status.Initial())

  val genResolved: Gen[Any, Ingestion.Status.Resolved] =
    genNonEmptySetString.map(Ingestion.Status.Resolved(_))

  val genProcessing: Gen[Any, Ingestion.Status.Processing] =
    for {
      allFiles <- genSetString
      remaining <- Gen.setOf(Gen.fromIterable(allFiles))
      processedAndFailed = allFiles -- remaining
      processed <- Gen.setOf(Gen.fromIterable(processedAndFailed))
      failed = processedAndFailed -- processed
      processing <- Gen.setOf(genString) // Optionally generate processing files
    } yield Ingestion.Status.Processing(remaining, processing, processed, failed)

  val genCompleted: Gen[Any, Ingestion.Status.Completed] =
    genNonEmptySetString.map(Ingestion.Status.Completed(_))

  val genFailed: Gen[Any, Ingestion.Status.Failed] =
    for {
      done <- genSetString
      failedFiles <- genNonEmptySetString
    } yield Ingestion.Status.Failed(done, failedFiles)

  val genStopped: Gen[Any, Ingestion.Status.Stopped] =
    for {
      processing <- genProcessing
      timestamp <- timestampGen
    } yield Ingestion.Status.Stopped(processing, timestamp)

  val genStatus: Gen[Any, Ingestion.Status] = Gen.oneOf(
    genInitial,
    genResolved,
    genProcessing,
    genCompleted,
    genFailed,
    genStopped,
  )
}
