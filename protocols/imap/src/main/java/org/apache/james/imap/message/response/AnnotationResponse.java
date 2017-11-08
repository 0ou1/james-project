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

package org.apache.james.imap.message.response;

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import org.apache.james.imap.api.message.response.ImapResponseMessage;
import org.apache.james.imap.message.model.MailboxName;
import org.apache.james.mailbox.model.MailboxAnnotation;

import com.google.common.base.Objects;

public class AnnotationResponse implements ImapResponseMessage {
    private final MailboxName mailboxName;
    private final List<MailboxAnnotation> mailboxAnnotations;

    public AnnotationResponse(MailboxName mailboxName, List<MailboxAnnotation> mailboxAnnotations) {
        this.mailboxName = mailboxName;
        this.mailboxAnnotations = ImmutableList.copyOf(mailboxAnnotations);
    }

    public MailboxName getMailboxName() {
        return mailboxName;
    }

    public List<MailboxAnnotation> getMailboxAnnotations() {
        return mailboxAnnotations;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mailboxName, mailboxAnnotations);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof AnnotationResponse) {
            AnnotationResponse o = (AnnotationResponse) obj;
            return Objects.equal(mailboxName, o.getMailboxName()) && Objects.equal(mailboxAnnotations, o.getMailboxAnnotations());
        }

        return false;
    }

    public String toString() {
        return MoreObjects.toStringHelper(AnnotationResponse.class)
                .add("mailboxName", mailboxName)
                .add("mailboxAnnotation", mailboxAnnotations)
                .toString();
    }

}
