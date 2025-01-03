package com.youtoo
package job
package repository

import com.youtoo.cqrs.service.*
import com.youtoo.cqrs.service.postgres.*

import com.youtoo.cqrs.Codecs.given

import com.youtoo.job.model.*
import zio.*
import zio.jdbc.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.common.*
import zio.schema.codec.*

trait JobRepository {
  def load(id: Job.Id): RIO[ZConnection, Option[Job]]
  def loadMany(offset: Option[Key], limit: Long): ZIO[ZConnection, Throwable, Chunk[Job]]
  def save(job: Job): RIO[ZConnection, Long]
}

object JobRepository {
  inline def load(id: Job.Id): RIO[JobRepository & ZConnection, Option[Job]] =
    ZIO.serviceWithZIO[JobRepository](_.load(id))

  inline def loadMany(offset: Option[Key], limit: Long): RIO[JobRepository & ZConnection, Chunk[Job]] =
    ZIO.serviceWithZIO[JobRepository](_.loadMany(offset, limit))

  inline def save(job: Job): RIO[JobRepository & ZConnection, Long] =
    ZIO.serviceWithZIO[JobRepository](_.save(job))

  def live(): ZLayer[Tracing, Throwable, JobRepository] =
    ZLayer.fromFunction(new JobRepositoryLive().traced(_))

  class JobRepositoryLive() extends JobRepository { self =>
    def load(id: Job.Id): RIO[ZConnection, Option[Job]] =
      Queries.READ_JOB(id).selectOne

    def save(job: Job): RIO[ZConnection, Long] =
      Queries.SAVE_JOB(job).insert

    def loadMany(offset: Option[Key], limit: Long): ZIO[ZConnection, Throwable, Chunk[Job]] =
      Queries
        .READ_JOBS(offset, limit)
        .selectAll

    def traced(tracing: Tracing): JobRepository =
      new JobRepository {
        def load(id: Job.Id): RIO[ZConnection, Option[Job]] =
          self.load(id) @@ tracing.aspects.span(
            "JobRepository.load",
            attributes = Attributes(Attribute.long("jobId", id.asKey.value)),
          )
        def loadMany(offset: Option[Key], limit: Long): RIO[ZConnection, Chunk[Job]] =
          self.loadMany(offset, limit) @@ tracing.aspects.span(
            "JobRepository.loadMany",
            attributes = Attributes(
              Attribute.long("offset", offset.map(_.value).getOrElse(0L)),
              Attribute.long("limit", limit),
            ),
          )
        def save(job: Job): RIO[ZConnection, Long] =
          self.save(job) @@ tracing.aspects.span(
            "JobRepository.save",
            attributes = Attributes(Attribute.long("jobId", job.id.asKey.value)),
          )
      }
  }

  object Queries extends JdbcCodecs {

    given JdbcDecoder[JobStatus] = byteArrayDecoder[JobStatus]
    given JdbcDecoder[JobMeasurement] = byteArrayDecoder[JobMeasurement]
    given JdbcDecoder[Job.Id] = JdbcDecoder[Long].map(n => Job.Id(Key(n)))
    given JdbcDecoder[Job.Tag] = JdbcDecoder[String].map(n => Job.Tag(n))

    given SqlFragment.Setter[Job.Id] = SqlFragment.Setter[Key].contramap(_.asKey)
    given SqlFragment.Setter[Job.Tag] = SqlFragment.Setter[String].contramap(_.value)

    inline def READ_JOB(id: Job.Id): Query[Job] =
      sql"""
      SELECT id, tag, total, status
      FROM jobs
      WHERE id = $id
      """.query[(Job.Id, Job.Tag, JobMeasurement, JobStatus)].map(Job.apply)

    inline def SAVE_JOB(job: Job): SqlFragment =
      val status =
        java.util.Base64.getEncoder.encodeToString(summon[BinaryCodec[JobStatus]].encode(job.status).toArray)

      val total =
        java.util.Base64.getEncoder.encodeToString(summon[BinaryCodec[JobMeasurement]].encode(job.total).toArray)

      sql"""
      INSERT INTO jobs (id, tag, total, status, created, modified)
      VALUES (
        ${job.id},
        ${job.tag},
        decode($total, 'base64'),
        decode($status, 'base64'),
        ${job.created},
        ${job.lastModified}
      )
      ON CONFLICT (id) DO UPDATE
      SET status = decode($status, 'base64')
      """

    inline def READ_JOBS(offset: Option[Key], limit: Long): Query[Job] =
      offset match {
        case None =>
          sql"""
          SELECT id, tag, total, status
          FROM jobs
          ORDER BY id DESC
          LIMIT $limit
          """.query[(Job.Id, Job.Tag, JobMeasurement, JobStatus)].map(Job.apply)

        case Some(key) =>
          sql"""
          SELECT id, tag, total, status
          FROM jobs
          WHERE id < $key
          ORDER BY id DESC
          LIMIT $limit
          """.query[(Job.Id, Job.Tag, JobMeasurement, JobStatus)].map(Job.apply)

      }
  }

}
