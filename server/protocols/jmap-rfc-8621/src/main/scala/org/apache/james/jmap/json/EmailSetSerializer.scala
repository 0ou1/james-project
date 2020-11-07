/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.jmap.json

import cats.implicits._
import eu.timepit.refined.refineV
import javax.inject.Inject
import org.apache.james.jmap.mail.EmailSet.{UnparsedMessageId, UnparsedMessageIdConstraint}
import org.apache.james.jmap.mail._
import org.apache.james.jmap.model.KeywordsFactory.STRICT_KEYWORDS_FACTORY
import org.apache.james.jmap.model.{Keyword, Keywords, SetError}
import org.apache.james.mailbox.model.{MailboxId, MessageId}
import play.api.libs.json._

import scala.util.Try

class EmailSetSerializer @Inject()(messageIdFactory: MessageId.Factory, mailboxIdFactory: MailboxId.Factory) {
  object EmailSetUpdateReads {
    def reads(jsObject: JsObject): JsResult[EmailSetUpdate] =
      asEmailSetUpdate(jsObject.value.map {
        case (property, value) => EntryValidation.from(property, value)
      }.toSeq)

    private def asEmailSetUpdate(entries: Seq[EntryValidation]): JsResult[EmailSetUpdate] =
      entries.flatMap(_.asJsError)
        .headOption
        .getOrElse({
          val mailboxReset: Option[MailboxIds] = entries.flatMap {
            case update: MailboxReset => Some(update)
            case _ => None
          }.headOption
            .map(_.ids)

          val keywordsReset: Option[Keywords] = entries.flatMap {
            case update: KeywordsReset => Some(update)
            case _ => None
          }.headOption
            .map(_.keywords)

          val mailboxesToAdd: Option[MailboxIds] = Some(entries
            .flatMap {
              case update: MailboxAddition => Some(update)
              case _ => None
            }.map(_.id).toList)
            .filter(_.nonEmpty)
            .map(MailboxIds)

          val mailboxesToRemove: Option[MailboxIds] = Some(entries
            .flatMap {
              case update: MailboxRemoval => Some(update)
              case _ => None
            }.map(_.id).toList)
            .filter(_.nonEmpty)
            .map(MailboxIds)

          val keywordsToAdd: Try[Option[Keywords]] = Some(entries
            .flatMap {
              case update: KeywordAddition => Some(update)
              case _ => None
            }.map(_.keyword).toSet)
            .filter(_.nonEmpty)
            .map(STRICT_KEYWORDS_FACTORY.fromSet)
            .sequence

          val keywordsToRemove: Try[Option[Keywords]] = Some(entries
            .flatMap {
              case update: KeywordRemoval => Some(update)
              case _ => None
            }.map(_.keyword).toSet)
            .filter(_.nonEmpty)
            .map(STRICT_KEYWORDS_FACTORY.fromSet)
            .sequence

          keywordsToAdd.flatMap(maybeKeywordsToAdd => keywordsToRemove
            .map(maybeKeywordsToRemove => (maybeKeywordsToAdd, maybeKeywordsToRemove)))
            .fold(e => JsError(e.getMessage),
              {
                case (maybeKeywordsToAdd, maybeKeywordsToRemove) => JsSuccess(EmailSetUpdate(keywords = keywordsReset,
                  keywordsToAdd = maybeKeywordsToAdd,
                  keywordsToRemove = maybeKeywordsToRemove,
                  mailboxIds = mailboxReset,
                  mailboxIdsToAdd = mailboxesToAdd,
                  mailboxIdsToRemove = mailboxesToRemove))
              })
        })

    object EntryValidation {
      private val mailboxIdPrefix: String = "mailboxIds/"
      private val keywordsPrefix: String = "keywords/"

      def from(property: String, value: JsValue): EntryValidation = property match {
        case "mailboxIds" => mailboxIdsReads.reads(value)
          .fold(
            e => InvalidPatchEntryValue(property, e.toString()),
            MailboxReset)
        case "keywords" => keywordsReads.reads(value)
          .fold(
            e => InvalidPatchEntryValue(property, e.toString()),
            KeywordsReset)
        case name if name.startsWith(mailboxIdPrefix) => Try(mailboxIdFactory.fromString(name.substring(mailboxIdPrefix.length)))
          .fold(e => InvalidPatchEntryNameWithDetails(property, e.getMessage),
            id => value match {
              case JsBoolean(true) => MailboxAddition(id)
              case JsNull => MailboxRemoval(id)
              case _ => InvalidPatchEntryValue(property, "MailboxId partial updates requires a JsBoolean(true) (set) or a JsNull (unset)")
            })
        case name if name.startsWith(keywordsPrefix) => Keyword.parse(name.substring(keywordsPrefix.length))
          .fold(e => InvalidPatchEntryNameWithDetails(property, e),
            keyword => value match {
              case JsBoolean(true) => KeywordAddition(keyword)
              case JsNull => KeywordRemoval(keyword)
              case _ => InvalidPatchEntryValue(property, "Keywords partial updates requires a JsBoolean(true) (set) or a JsNull (unset)")
            })
        case _ => InvalidPatchEntryName(property)
      }
    }

    sealed trait EntryValidation {
      def asJsError: Option[JsError] = None
    }

    private case class InvalidPatchEntryName(property: String) extends EntryValidation {
      override def asJsError: Option[JsError] = Some(JsError(s"$property is an invalid entry in an Email/set update patch"))
    }

    private case class InvalidPatchEntryNameWithDetails(property: String, cause: String) extends EntryValidation {
      override def asJsError: Option[JsError] = Some(JsError(s"$property is an invalid entry in an Email/set update patch: $cause"))
    }

    private case class InvalidPatchEntryValue(property: String, cause: String) extends EntryValidation {
      override def asJsError: Option[JsError] = Some(JsError(s"Value associated with $property is invalid: $cause"))
    }

    private case class MailboxAddition(id: MailboxId) extends EntryValidation

    private case class MailboxRemoval(id: MailboxId) extends EntryValidation

    private case class MailboxReset(ids: MailboxIds) extends EntryValidation

    private case class KeywordsReset(keywords: Keywords) extends EntryValidation

    private case class KeywordAddition(keyword: Keyword) extends EntryValidation

    private case class KeywordRemoval(keyword: Keyword) extends EntryValidation

  }

  private implicit val messageIdWrites: Writes[MessageId] = messageId => JsString(messageId.serialize)
  private implicit val messageIdReads: Reads[MessageId] = {
    case JsString(serializedMessageId) => Try(JsSuccess(messageIdFactory.fromString(serializedMessageId)))
      .fold(_ => JsError("Invalid messageId"), messageId => messageId)
    case _ => JsError("Expecting messageId to be represented by a JsString")
  }

  private implicit val mailboxIdsMapReads: Reads[Map[MailboxId, Boolean]] =
    Reads.mapReads[MailboxId, Boolean] {s => Try(mailboxIdFactory.fromString(s)).fold(e => JsError(e.getMessage), JsSuccess(_)) } (mapMarkerReads)

  private implicit val mailboxIdsReads: Reads[MailboxIds] = jsValue => mailboxIdsMapReads.reads(jsValue).map(
    mailboxIdsMap => MailboxIds(mailboxIdsMap.keys.toList))

  private implicit val emailSetUpdateReads: Reads[EmailSetUpdate] = {
    case o: JsObject => EmailSetUpdateReads.reads(o)
    case _ => JsError("Expecting a JsObject to represent an EmailSetUpdate")
  }

  private implicit val updatesMapReads: Reads[Map[UnparsedMessageId, JsObject]] =
    Reads.mapReads[UnparsedMessageId, JsObject] {string => refineV[UnparsedMessageIdConstraint](string).fold(JsError(_), id => JsSuccess(id)) }

  private implicit val keywordReads: Reads[Keyword] = {
    case jsString: JsString => Keyword.parse(jsString.value)
      .fold(JsError(_),
        JsSuccess(_))
    case _ => JsError("Expecting a string as a keyword")
  }

  private implicit val keywordsMapReads: Reads[Map[Keyword, Boolean]] =
    Reads.mapReads[Keyword, Boolean] {string => Keyword.parse(string).fold(JsError(_), JsSuccess(_)) } (mapMarkerReads)
  private implicit val keywordsReads: Reads[Keywords] = jsValue => keywordsMapReads.reads(jsValue).flatMap(
    keywordsMap => STRICT_KEYWORDS_FACTORY.fromSet(keywordsMap.keys.toSet)
      .fold(e => JsError(e.getMessage), keywords => JsSuccess(keywords)))

  private implicit val unitWrites: Writes[Unit] = _ => JsNull
  private implicit val updatedWrites: Writes[Map[MessageId, Unit]] = mapWrites[MessageId, Unit](_.serialize, unitWrites)
  private implicit val notDestroyedWrites: Writes[Map[UnparsedMessageId, SetError]] = mapWrites[UnparsedMessageId, SetError](_.value, setErrorWrites)
  private implicit val destroyIdsReads: Reads[DestroyIds] = {
    Json.valueFormat[DestroyIds]
  }
  private implicit val destroyIdsWrites: Writes[DestroyIds] = Json.valueWrites[DestroyIds]
  private implicit val emailRequestSetReads: Reads[EmailSetRequest] = Json.reads[EmailSetRequest]
  private implicit val emailResponseSetWrites: OWrites[EmailSetResponse] = Json.writes[EmailSetResponse]

  def deserialize(input: JsValue): JsResult[EmailSetRequest] = Json.fromJson[EmailSetRequest](input)

  def deserializeEmailSetUpdate(input: JsValue): JsResult[EmailSetUpdate] = Json.fromJson[EmailSetUpdate](input)

  def serialize(response: EmailSetResponse): JsObject = Json.toJsObject(response)
}
