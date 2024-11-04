package com.youtoo
package ingestion
package model

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.*
import zio.*
import com.youtoo.cqrs.*
import com.youtoo.cqrs.domain.*
import com.youtoo.cqrs.Codecs.given

object LoadIngestionFileByNameSpec extends ZIOSpecDefault {
  def spec = suite("LoadIngestionFileByNameSpec")(
    test("should load IngestionFile by name from events") {
      check(providerIdGen, fileIdGen, fileNameGen, fileSigGen, versionGen, ingestionFileMetadataGen) {
        (providerId, fileId, fileName, fileSig, version, metadata) =>
          val handler = new FileEvent.LoadIngestionFileByName(fileName)

          val events = NonEmptyList(
            Change(
              version = version,
              payload = FileEvent.FileAdded(
                provider = providerId,
                id = fileId,
                name = fileName,
                metadata = metadata,
                sig = fileSig,
              ),
            ),
          )

          val result = handler.applyEvents(events)

          val expectedFile = IngestionFile(
            id = fileId,
            name = fileName,
            metadata = metadata,
            sig = fileSig,
          )

          assert(result)(isSome(equalTo(expectedFile)))
      }
    },
    test("should return None if file with given name is not in events") {
      check(fileNameGen, fileNameGen, versionGen, ingestionFileMetadataGen) {
        (fileName, differentFileName, version, metadata) =>
          val handler = new FileEvent.LoadIngestionFileByName(fileName)

          val events = NonEmptyList(
            Change(
              version = version,
              payload = FileEvent.FileAdded(
                provider = Provider.Id("provider-1"),
                id = IngestionFile.Id("file-1"),
                name = differentFileName, // Different name
                metadata = metadata,
                sig = IngestionFile.Sig("signature"),
              ),
            ),
          )

          val result = handler.applyEvents(events)

          assert(result)(if fileName == differentFileName then isSome else isNone)
      }
    },
  )
}
