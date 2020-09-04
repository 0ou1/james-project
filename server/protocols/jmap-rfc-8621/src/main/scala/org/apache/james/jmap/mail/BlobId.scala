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

package org.apache.james.jmap.mail

import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.james.mailbox.model.MessageId

import scala.util.{Failure, Success, Try}

object BlobId {
  def of(string: String): Try[BlobId] = refineV[NonEmpty](string) match {
      case scala.Right(value) => Success(BlobId(value))
      case Left(e) => Failure(new IllegalArgumentException(e))
    }
  def of(messageId: MessageId): Try[BlobId] = of(messageId.serialize())
  def of(messageId: MessageId, partId: PartId): Try[BlobId] = of(s"${messageId.serialize()}_${partId.serialize}")
}

case class BlobId(value: NonEmptyString)
