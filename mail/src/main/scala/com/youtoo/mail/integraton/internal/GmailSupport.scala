package com.youtoo
package mail
package integration
package internal

import zio.*

import com.youtoo.mail.model.*

import com.google.api.client.googleapis.auth.oauth2.*

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail

import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.UserCredentials

import com.google.api.client.auth.oauth2.TokenResponseException

object GmailSupport {
  val jsonFactory = GsonFactory.getDefaultInstance

  private case class GoogleTransport(transport: NetHttpTransport) extends AnyVal

  def httpTransport(): ZLayer[Any, Throwable, NetHttpTransport] =
    ZLayer.scoped {
      ZIO
        .acquireRelease(
          ZIO.attempt {
            GoogleTransport(GoogleNetHttpTransport.newTrustedTransport())
          },
        )(g => ZIO.attemptBlocking(g.transport.shutdown()).ignoreLogged)
        .map(_.transport)
    }

  def isAuthorizationRevoked: Throwable => Boolean = {
    case e: TokenResponseException =>
      Option(e.getDetails).fold(false)(_.getError == "invalid_grant")

    case _ => false
  }

  def getToken(info: GoogleClientInfo, code: String): RIO[NetHttpTransport, TokenInfo] =
    ZIO.serviceWithZIO[NetHttpTransport] { (httpTransport: NetHttpTransport) =>
      ZIO.attempt {

        val ts: GoogleTokenResponse = new GoogleAuthorizationCodeTokenRequest(
          httpTransport,
          jsonFactory,
          "https://oauth2.googleapis.com/token",
          info.clientId.value,
          info.clientSecret.value,
          code,
          info.redirectUri.value,
        ).setGrantType("authorization_code").execute()

        TokenInfo(
          refreshToken = TokenInfo.RefreshToken(ts.getRefreshToken()),
          idToken = Option(ts.getIdToken()) map TokenInfo.IdToken.apply,
        )
      }
    }

  def getClient(clientInfo: GoogleClientInfo, tokenInfo: TokenInfo): RIO[Scope & NetHttpTransport, Gmail] =
    ZIO.serviceWithZIO[NetHttpTransport] { (httpTransport: NetHttpTransport) =>
      ZIO.attempt {

        val credentials = UserCredentials
          .newBuilder()
          .setClientId(clientInfo.clientId.value)
          .setClientSecret(clientInfo.clientSecret.value)
          .setRefreshToken(tokenInfo.refreshToken.value)
          .build()

        val requestInitializer = new HttpCredentialsAdapter(credentials)

        new Gmail.Builder(httpTransport, jsonFactory, requestInitializer)
          .setApplicationName("YouToo Mail App")
          .build()
      }

    }

}
