/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *  http://www.apache.org/licenses/LICENSE-2.0                  *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.jmap.routes

import java.io.InputStream
import java.util.stream
import java.util.stream.Stream

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus.{BAD_REQUEST, CREATED}
import javax.inject.{Inject, Named}
import org.apache.commons.fileupload.util.LimitedInputStream
import org.apache.james.jmap.core.Id.Id
import org.apache.james.jmap.core.{AccountId, Id, JmapRfc8621Configuration}
import org.apache.james.jmap.exceptions.UnauthorizedException
import org.apache.james.jmap.http.Authenticator
import org.apache.james.jmap.http.rfc8621.InjectionKeys
import org.apache.james.jmap.json.UploadSerializer
import org.apache.james.jmap.mail.BlobId
import org.apache.james.jmap.mail.Email.Size
import org.apache.james.jmap.routes.UploadRoutes.{LOGGER, sanitizeSize}
import org.apache.james.jmap.{Endpoint, JMAPRoute, JMAPRoutes}
import org.apache.james.mailbox.model.{AttachmentMetadata, ContentType}
import org.apache.james.mailbox.{AttachmentManager, MailboxSession}
import org.apache.james.util.ReactorUtils
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.Mono
import reactor.core.scala.publisher.SMono
import reactor.core.scheduler.Schedulers
import reactor.netty.http.server.{HttpServerRequest, HttpServerResponse}

case class TooBogUploadException() extends RuntimeException

object UploadRoutes {
  val LOGGER: Logger = LoggerFactory.getLogger(classOf[DownloadRoutes])

  type Size = Long Refined NonNegative
  val Zero: Size = 0L

  def sanitizeSize(value: Long): Size = {
    val size: Either[String, Size] = refineV[NonNegative](value)
    size.fold(e => {
      LOGGER.error(s"Encountered an invalid upload files size: $e")
      Zero
    },
      refinedValue => refinedValue)
  }
}

case class UploadResponse(accountId: AccountId,
                          blobId: BlobId,
                          `type`: ContentType,
                          size: Size)

class UploadRoutes @Inject()(@Named(InjectionKeys.RFC_8621) val authenticator: Authenticator,
                             val configuration: JmapRfc8621Configuration,
                             val attachmentManager: AttachmentManager,
                             val serializer: UploadSerializer) extends JMAPRoutes {

  class CancelledUploadException extends RuntimeException
  class MaxFileSizeUploadException extends RuntimeException

  private val accountIdParam: String = "accountId"
  private val uploadURI = s"/upload/{$accountIdParam}/"

  override def routes(): stream.Stream[JMAPRoute] = Stream.of(
    JMAPRoute.builder
      .endpoint(new Endpoint(HttpMethod.POST, uploadURI))
      .action(this.post)
      .corsHeaders,
    JMAPRoute.builder
      .endpoint(new Endpoint(HttpMethod.OPTIONS, uploadURI))
      .action(JMAPRoutes.CORS_CONTROL)
      .noCorsHeaders)

  def post(request: HttpServerRequest, response: HttpServerResponse): Mono[Void] = {
    request.requestHeaders.get(CONTENT_TYPE) match {
      case contentType => SMono.fromPublisher(
        authenticator.authenticate(request))
        .flatMap(session => post(request, response, ContentType.of(contentType), session))
        .onErrorResume {
          case e: TooBogUploadException => SMono.fromPublisher(response.status(BAD_REQUEST).sendString(SMono.just("Attempt to upload exceed max size")).`then`())
          case e: UnauthorizedException => SMono.fromPublisher(handleAuthenticationFailure(response, LOGGER, e))
          case e: Throwable => SMono.fromPublisher(handleInternalError(response, LOGGER, e))
        }
        .asJava()
        .subscribeOn(Schedulers.elastic())
        .`then`()
      case _ => response.status(BAD_REQUEST).send
    }
  }

  def post(request: HttpServerRequest, response: HttpServerResponse, contentType: ContentType, session: MailboxSession): SMono[Void] = {
    Id.validate(request.param(accountIdParam)) match {
      case Right(id: Id) =>
        val targetAccountId: AccountId = AccountId(id)
        AccountId.from(session.getUser).map(accountId => accountId.equals(targetAccountId))
          .fold[SMono[Void]](
            e => SMono.raiseError(e),
            value => if (value) {
              SMono.fromCallable(() => ReactorUtils.toInputStream(request.receive.asByteBuffer))
              .flatMap(content => handle(targetAccountId, contentType, content, session, response))
            } else {
              SMono.raiseError(new UnauthorizedException("Attempt to upload in another account"))
            })

      case Left(throwable: Throwable) => SMono.raiseError(throwable)
    }
  }

  def handle(accountId: AccountId, contentType: ContentType, content: InputStream, mailboxSession: MailboxSession, response: HttpServerResponse): SMono[Void] = {
    val maxSize: Long = configuration.maxUploadSize.value.value

    SMono.fromCallable(() => new LimitedInputStream(content, maxSize) {
      override def raiseError(max: Long, count: Long): Unit = if (count > max) {
        throw TooBogUploadException()
      }})
      .flatMap(uploadContent(accountId, contentType, _, mailboxSession))
      .flatMap(uploadResponse => SMono.fromPublisher(response
              .header(CONTENT_TYPE, uploadResponse.`type`.asString())
              .status(CREATED)
              .sendString(SMono.just(serializer.serialize(uploadResponse).toString()))))
  }


  def uploadContent(accountId: AccountId, contentType: ContentType, inputStream: InputStream, session: MailboxSession): SMono[UploadResponse] =
    SMono
      .fromPublisher(attachmentManager.storeAttachment(contentType, inputStream, session))
      .map(fromAttachment(_, accountId))

  private def fromAttachment(attachmentMetadata: AttachmentMetadata, accountId: AccountId): UploadResponse =
    UploadResponse(
        blobId = BlobId.of(attachmentMetadata.getAttachmentId.getId).get,
        `type` = ContentType.of(attachmentMetadata.getType.asString),
        size = sanitizeSize(attachmentMetadata.getSize),
        accountId = accountId)
}
