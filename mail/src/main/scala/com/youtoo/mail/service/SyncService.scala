package com.youtoo
package mail
package service

import zio.*
import zio.prelude.*

import com.youtoo.mail.model.*
import com.youtoo.job.model.*
import com.youtoo.job.service.*

import com.youtoo.lock.*

import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.common.*

import zio.stream.*
import com.youtoo.mail.integration.*

import java.time.temporal.ChronoUnit

import com.youtoo.mail.integration.internal.GmailSupport

trait SyncService {
  def sync(id: MailAccount.Id, options: SyncOptions): ZIO[Scope & Tracing, Throwable, Unit]
}

object SyncService {
  val MailSync = Job.Tag("MailSync")

  inline def sync(id: MailAccount.Id): ZIO[
    Scope & SyncService & Tracing,
    Throwable,
    Unit,
  ] =
    for {
      options <- ZIO.config[SyncOptions]
      _ <- ZIO.serviceWithZIO[SyncService](_.sync(id, options))
    } yield ()

  inline def sync(id: MailAccount.Id, options: SyncOptions): ZIO[
    Scope & SyncService & Tracing,
    Throwable,
    Unit,
  ] =
    ZIO.serviceWithZIO[SyncService](_.sync(id, options))

  def live(): ZLayer[
    LockManager & MailClient & MailService & JobService & Tracing,
    Nothing,
    SyncService,
  ] =
    ZLayer.fromFunction {
      (
        mailClient: MailClient,
        mailService: MailService,
        jobService: JobService,
        lockManager: LockManager,
        tracing: Tracing,
      ) =>
        new SyncServiceLive(
          mailClient,
          mailService,
          jobService,
          lockManager,
        ).traced(tracing)
    }

  class SyncServiceLive(
    mailClient: MailClient,
    mailService: MailService,
    jobService: JobService,
    lockManager: LockManager,
  ) extends SyncService { self =>

    def sync(id: MailAccount.Id, options: SyncOptions): ZIO[Scope & Tracing, Throwable, Unit] =
      mailService.loadAccount(id).flatMap {
        case None => ZIO.fail(new IllegalArgumentException(s"Account with key $id not found"))
        case Some(account) =>
          scopedTask { interruption =>
            withLock(account) {
              for {
                state <- mailService.loadState(account.id)
                _ <- state match {
                  case Some(mail) if !mail.authorization.isGranted() => Log.error("Mail account not authorized")
                  case Some(mail) => performSync(account, mail, options, interruption)
                  case None => Log.error("Sync state not found")
                }
              } yield ()
            }
          }
      }

    def scopedTask[R, E, A](task: Ref[Boolean] => ZIO[R & Scope, E, A]): ZIO[R & Tracing & Scope, E, A] =
      ZIO.acquireRelease {
        for {
          interruption <- Ref.make(false)
          fiber <- ZIO.scoped(task(interruption)).fork

        } yield (
          interruption,
          fiber,
        )
      } {
        case (
              interruption,
              fiber,
            ) =>
          for {
            _ <- Log.error("Sync interruption requested")
            _ <- interruption.set(true).fork

            _ <- fiber.join
              .timeoutFail(new IllegalStateException("timeout"))(Duration(30L, ChronoUnit.SECONDS))
              .ignoreLogged

          } yield ()
      }.flatMap {
        case (
              _,
              fiber,
            ) =>
          fiber.join
      }

    private def withLock(account: MailAccount)(
      action: => ZIO[Scope & Tracing, Throwable, Unit],
    ): ZIO[Scope & Tracing, Throwable, Unit] =
      val lock = lockManager.acquireScoped(account.syncLock)
      ZIO.ifZIO(lock)(
        onTrue = action,
        onFalse = Log.info(s"Sync already in progress for account"),
      )

    private def performSync(
      account: MailAccount,
      mail: Mail,
      options: SyncOptions,
      interruption: Ref[Boolean],
    ): ZIO[Scope & Tracing, Throwable, Unit] =
      ZIO.uninterruptibleMask { restore =>
        for {
          timestamp <- Timestamp.gen
          jobId <- Job.Id.gen
          _ <- jobService.startJob(id = jobId, timestamp, total = JobMeasurement.Variable(), tag = MailSync)
          _ <- mailService.startSync(accountKey = account.id, labels = MailLabels.All(), timestamp, jobId)
          reason <- fetchAndRecordMails(options, account, mail.cursor.map(_.token), jobId, restore, interruption)
            .foldZIO(
              success = cancelled =>
                ZIO.succeed(if cancelled then Job.CompletionReason.Cancellation() else Job.CompletionReason.Success()),
              failure = e =>
                account.accountType match {
                  case AccountType.Gmail =>
                    val reason = Job.CompletionReason.Failure(Option(e.getMessage))

                    if GmailSupport.isAuthorizationRevoked(e) then
                      for {
                        _ <- Log.error(s"Mail account token revoked: ${account.id}")
                        timestamp <- Timestamp.gen
                        _ <- mailService.revokeAuthorization(account.id, timestamp)

                      } yield reason
                    else ZIO.succeed(reason)
                },
            )
          endTimestamp <- Timestamp.gen
          _ <- mailService.completeSync(accountKey = account.id, endTimestamp, jobId)
          _ <- jobService.completeJob(id = jobId, timestamp = endTimestamp, reason = reason)
          _ <- reason match {
            case Job.CompletionReason.Success() => Log.info("Sync complete")
            case Job.CompletionReason.Cancellation() => Log.info("Sync cancelled")
            case Job.CompletionReason.Failure(m) => Log.error(s"""Sync failed: ${m.getOrElse("<unknown>")}""")
          }
        } yield ()
      }

    private def fetchAndRecordMails(
      options: SyncOptions,
      account: MailAccount,
      token: Option[MailToken],
      jobId: Job.Id,
      restore: ZIO.InterruptibilityRestorer,
      interruption: Ref[Boolean],
    ): ZIO[Scope & Tracing, Throwable, Boolean] =
      (
        Ref.make(false) <&> Timestamp.gen
      ) flatMap { case (cancelledRef, timestamp) =>
        val init = SyncState(started = timestamp, token = token, iterations = 0)

        ZStream
          .unfoldZIO(init) { state =>
            val op = options.applyZIO {
              restore(mailClient.fetchMails(account.id, state.token, None))
            }

            Log.debug(s"Begin fetch for account from ${state.token}") *>
              op.flatMap {
                case None => ZIO.none
                case Some((mailKeys, nextToken)) =>
                  for {
                    _ <- Log.debug(s"Fetched ${mailKeys.size} mails for account")
                    timestamp <- Timestamp.gen
                    _ <- mailService.recordSynced(accountKey = account.id, timestamp, mailKeys, nextToken, jobId)
                    interrupted <- interruption.get
                    nextState = state.next(nextToken)
                    expired = nextState.isExpired(options, timestamp)
                    cancelled <- if !expired && !interrupted then jobService.isCancelled(jobId) else ZIO.succeed(false)
                    _ <- Log.info(s"Sync interrupted for account") when interrupted
                    _ <- Log.info(s"Sync cancelled for account") when cancelled
                    _ <- Log.info(s"Sync expired for account") when expired
                    _ <- cancelledRef.set(true) when cancelled
                  } yield
                    if cancelled || expired || interrupted then None
                    else Some((), nextState)
              }
          }
          .runDrain *> cancelledRef.get
      }

    def traced(tracing: Tracing): SyncService = new SyncService {

      def sync(id: MailAccount.Id, options: SyncOptions): ZIO[Scope & Tracing, Throwable, Unit] =
        self.sync(id, options) @@ tracing.aspects.span(
          "SyncService.sync",
          attributes = Attributes(Attribute.long("accountId", id.asKey.value)),
        )

    }

  }

  case class SyncState(started: Timestamp, token: Option[MailToken], iterations: Int)

  object SyncState {
    extension (s: SyncState)
      inline def next(token: MailToken): SyncState = s.copy(iterations = s.iterations + 1, token = Some(token))

    extension (s: SyncState)
      inline def isExpired(options: SyncOptions, timestamp: Timestamp): Boolean =
        val durationExpired = options.maxDuration match {
          case None => false
          case Some(duration) => duration.toMillis < (timestamp.value - s.started.value)
        }

        val iterationsExpired = options.maxIterations match {
          case None => false
          case Some(iterations) => iterations <= s.iterations
        }

        durationExpired || iterationsExpired

  }

}
