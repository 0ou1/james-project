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

package org.apache.james.mailbox.store;

import java.util.Collection;
import java.util.List;

import javax.inject.Inject;

import org.apache.james.mailbox.AttachmentManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageIdManager;
import org.apache.james.mailbox.exception.AttachmentNotFoundException;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.Attachment;
import org.apache.james.mailbox.model.AttachmentId;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.store.mail.AttachmentMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.steveash.guavate.Guavate;
import com.google.common.base.Throwables;

public class StoreAttachmentManager implements AttachmentManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreAttachmentManager.class);

    public enum Access {
        Authorized,
        Unreferenced,
        Forbidden
    }

    private final AttachmentMapperFactory attachmentMapperFactory;
    private final MessageIdManager messageIdManager;

    @Inject
    public StoreAttachmentManager(AttachmentMapperFactory attachmentMapperFactory, MessageIdManager messageIdManager) {
        this.attachmentMapperFactory = attachmentMapperFactory;
        this.messageIdManager = messageIdManager;
    }

    @Override
    public Attachment getAttachment(AttachmentId attachmentId, MailboxSession mailboxSession) throws MailboxException, AttachmentNotFoundException {
        if (userHasAccessToAttachment(attachmentId, mailboxSession) == Access.Forbidden) {
            throw new AttachmentNotFoundException(attachmentId.getId());
        }
        return attachmentMapperFactory.getAttachmentMapper(mailboxSession).getAttachment(attachmentId);
    }

    @Override
    public List<Attachment> getAttachments(List<AttachmentId> attachmentIds, MailboxSession mailboxSession) throws MailboxException {
        List<AttachmentId> accessibleAttachmentIds = attachmentIds.stream()
            .filter(attachmentId -> userHasAccessToAttachment(attachmentId, mailboxSession) != Access.Forbidden)
            .collect(Guavate.toImmutableList());

        return attachmentMapperFactory.getAttachmentMapper(mailboxSession).getAttachments(accessibleAttachmentIds);
    }

    @Override
    public void storeAttachment(Attachment attachment, MailboxSession mailboxSession) throws MailboxException {
        attachmentMapperFactory.getAttachmentMapper(mailboxSession).storeAttachment(attachment);
    }

    @Override
    public void storeAttachmentsForMessage(Collection<Attachment> attachments, MessageId ownerMessageId, MailboxSession mailboxSession) throws MailboxException {
        attachmentMapperFactory.getAttachmentMapper(mailboxSession).storeAttachmentsForMessage(attachments, ownerMessageId);
    }

    private Access userHasAccessToAttachment(AttachmentId attachmentId, MailboxSession mailboxSession) {
        try {
            Collection<MessageId> relatedMessageIds = getRelatedMessageIds(attachmentId, mailboxSession);
            if (relatedMessageIds.isEmpty()) {
                return Access.Unreferenced;
            }
            boolean hasAuthorizedMessageIds = messageIdManager
                .accessibleMessages(relatedMessageIds, mailboxSession)
                .isEmpty();
            if (hasAuthorizedMessageIds) {
                return Access.Authorized;
            }
            return Access.Forbidden;
        } catch (MailboxException e) {
            LOGGER.warn("Error while checking attachment related accessible message ids", e);
            throw Throwables.propagate(e);
        }
    }

    private Collection<MessageId> getRelatedMessageIds(AttachmentId attachmentId, MailboxSession mailboxSession) throws MailboxException {
        return attachmentMapperFactory.getAttachmentMapper(mailboxSession).getRelatedMessageIds(attachmentId);
    }

}
