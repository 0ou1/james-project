/** **************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 * *
 * http://www.apache.org/licenses/LICENSE-2.0                 *
 * *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 * ***************************************************************/

package org.apache.james.jmap.http

import java.util.Base64

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.refineV
import eu.timepit.refined.string.MatchesRegex
import javax.inject.Inject
import org.apache.james.core.Username
import org.apache.james.jmap.exceptions.UnauthorizedException
import org.apache.james.jmap.http.Authenticator.Authorization
import org.apache.james.jmap.http.UserCredential._
import org.apache.james.mailbox.{MailboxManager, MailboxSession}
import org.apache.james.user.api.UsersRepository
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scala.publisher.SMono
import reactor.netty.http.server.HttpServerRequest
import scala.jdk.OptionConverters._

import scala.util.{Failure, Success, Try}

object UserCredential {
  type BasicAuthenticationHeaderValue = String Refined MatchesRegex["Basic [\\d\\w=]++"]
  type CredentialsAsString = String Refined MatchesRegex[".*:.*"]

  private val logger = LoggerFactory.getLogger(classOf[UserCredential])
  private val BASIC_AUTHENTICATION_PREFIX: String = "Basic "

  def parseUserCredentials(token: Authorization): Option[UserCredential] = {
    val refinedValue: Either[String, BasicAuthenticationHeaderValue] = refineV(token.asString())

    refinedValue match {
      // Ignore Authentication headers not being Basic Auth
      case Left(_) => None
      case Right(value) => extractUserCredentialsAsString(value)
    }
  }

  private def extractUserCredentialsAsString(token: BasicAuthenticationHeaderValue): Option[UserCredential] = {
    val encodedCredentials = token.replace(BASIC_AUTHENTICATION_PREFIX, "")
    val decodedCredentialsString = new String(Base64.getDecoder.decode(encodedCredentials))
    val refinedValue: Either[String, CredentialsAsString] = refineV(decodedCredentialsString)

    refinedValue match {
      case Left(errorMessage: String) =>
        throw new UnauthorizedException(s"Supplied basic authentication credentials do not match expected format. $errorMessage")
      case Right(value) => toCredential(value)
    }
  }

  private def toCredential(token: CredentialsAsString): Option[UserCredential] = {
    val partSeparatorIndex: Int = token.indexOf(':')
    val usernameString: String = token.substring(0, partSeparatorIndex)
    val passwordString: String = token.substring(partSeparatorIndex + 1)

    Try(UserCredential(Username.of(usernameString), passwordString)) match {
      case Success(credential) => Some(credential)
      case Failure(throwable:IllegalArgumentException) =>
        throw new UnauthorizedException("Username is not valid", throwable)
      case Failure(unexpectedException) =>
        logger.error("Unexpected Exception", unexpectedException)
        throw unexpectedException
    }
  }
}

case class UserCredential(username: Username, password: String)

class BasicAuthenticationStrategy @Inject()(val usersRepository: UsersRepository,
                                            val mailboxManager: MailboxManager) extends AuthenticationStrategy {

  override def createMailboxSession(httpRequest: HttpServerRequest): Mono[MailboxSession] =
    authHeaders(httpRequest).toScala
      .map(createMailboxSession)
      .getOrElse(SMono.empty[MailboxSession].asJava())

  override def createMailboxSession(authorization: Authorization): Mono[MailboxSession] =
    parseUserCredentials(authorization)
      .map(credentials => {
        if(isValid(credentials)) {
          SMono.just(mailboxManager.createSystemSession(credentials.username))
        } else {
          SMono.error(new UnauthorizedException(s"Bad authentication for ${credentials.username.asString()}"))
        }
      }).getOrElse(SMono.empty)
      .asJava()

  private def isValid(userCredential: UserCredential): Boolean =
    usersRepository.test(userCredential.username, userCredential.password)
}
