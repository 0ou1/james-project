/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 * http://www.apache.org/licenses/LICENSE-2.0                   *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.jmap.utils.search

import java.util.Date

import org.apache.james.jmap.mail.EmailQueryRequest
import org.apache.james.mailbox.model.SearchQuery.DateResolution
import org.apache.james.mailbox.model.SearchQuery.DateResolution.Second
import org.apache.james.mailbox.model.{MultimailboxesSearchQuery, SearchQuery}

import scala.jdk.CollectionConverters._

sealed trait MailboxFilter {
  def toQuery(builder: MultimailboxesSearchQuery.Builder, request: EmailQueryRequest): MultimailboxesSearchQuery.Builder
}

case object InMailboxFilter extends MailboxFilter {
  override def toQuery(builder: MultimailboxesSearchQuery.Builder, request: EmailQueryRequest): MultimailboxesSearchQuery.Builder = request.filter.flatMap(_.inMailbox) match {
    case Some(mailboxId) => builder.inMailboxes(mailboxId)
    case None => builder
  }
}

case object NotInMailboxFilter extends MailboxFilter {
  override def toQuery(builder: MultimailboxesSearchQuery.Builder, request: EmailQueryRequest): MultimailboxesSearchQuery.Builder = request.filter.flatMap(_.inMailboxOtherThan) match {
    case Some(mailboxIds) => builder.notInMailboxes(mailboxIds.asJava)
    case None => builder
  }
}

object MailboxFilter {
  def buildQuery(request: EmailQueryRequest, searchQuery: SearchQuery) = {
    val multiMailboxQueryBuilder = MultimailboxesSearchQuery.from(searchQuery)

    List(InMailboxFilter, NotInMailboxFilter).foldLeft(multiMailboxQueryBuilder)((builder, filter) => filter.toQuery(builder, request))
      .build()
  }

  sealed trait QueryFilter {
    def toQuery(builder: SearchQuery.Builder, request: EmailQueryRequest): SearchQuery.Builder
  }

  object QueryFilter {
    def buildQuery(request: EmailQueryRequest): SearchQuery.Builder = {
      List(ReceivedBefore, ReceivedAfter, HasAttachment, HasKeyWord, NotKeyWord)
        .foldLeft(SearchQuery.builder())((builder, filter) => filter.toQuery(builder, request))
    }
  }

  case object ReceivedBefore extends QueryFilter {
    override def toQuery(builder: SearchQuery.Builder, request: EmailQueryRequest): SearchQuery.Builder =  request.filter.flatMap(_.before) match {
      case Some(before) =>
        val strictlyBefore = SearchQuery.internalDateBefore(Date.from(before.asUTC.toInstant), Second)
        val sameDate = SearchQuery.internalDateOn(Date.from(before.asUTC.toInstant), Second)
        builder
          .andCriteria(SearchQuery.or(strictlyBefore, sameDate))
      case None => builder
    }
  }

  case object ReceivedAfter extends QueryFilter {
    override def toQuery(builder: SearchQuery.Builder, request: EmailQueryRequest): SearchQuery.Builder = request.filter.flatMap(_.after) match {
      case Some(after) =>
        val strictlyAfter = SearchQuery.internalDateAfter(Date.from(after.asUTC.toInstant), DateResolution.Second)
        builder
          .andCriteria(strictlyAfter)
      case None => builder
    }
  }

  case object HasAttachment extends QueryFilter {
    override def toQuery(builder: SearchQuery.Builder, request: EmailQueryRequest): SearchQuery.Builder =
      request.filter.flatMap(_.hasAttachment) match {
        case Some(hasAttachment) => builder
          .andCriteria(SearchQuery.hasAttachment(hasAttachment.value))
        case None => builder
      }
  }

  case object HasKeyWord extends QueryFilter {
    override def toQuery(builder: SearchQuery.Builder, request: EmailQueryRequest): SearchQuery.Builder =  request.filter.flatMap(_.hasKeyword) match {
      case Some(keyword) =>
        keyword.asSystemFlag match {
          case Some(systemFlag) => builder.andCriteria(SearchQuery.flagIsSet(systemFlag))
          case None => builder.andCriteria(SearchQuery.flagIsSet(keyword.flagName))
        }
      case None => builder
    }
  }
  case object NotKeyWord extends QueryFilter {
    override def toQuery(builder: SearchQuery.Builder, request: EmailQueryRequest): SearchQuery.Builder =  request.filter.flatMap(_.notKeyword) match {
      case Some(keyword) =>
        keyword.asSystemFlag match {
          case Some(systemFlag) => builder.andCriteria(SearchQuery.flagIsUnSet(systemFlag))
          case None => builder.andCriteria(SearchQuery.flagIsUnSet(keyword.flagName))
        }
      case None => builder
    }
  }
}
