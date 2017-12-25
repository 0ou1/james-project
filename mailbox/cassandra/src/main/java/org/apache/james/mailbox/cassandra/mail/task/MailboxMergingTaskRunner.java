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

package org.apache.james.mailbox.cassandra.mail.task;

import javax.inject.Inject;

import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.acl.ACLDiff;
import org.apache.james.mailbox.cassandra.ids.CassandraId;
import org.apache.james.mailbox.cassandra.mail.CassandraACLMapper;
import org.apache.james.mailbox.cassandra.mail.CassandraMailboxDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraMessageIdDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraUserMailboxRightsDAO;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.ComposedMessageIdWithMetaData;
import org.apache.james.mailbox.model.MailboxACL;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.store.StoreMessageIdManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MailboxMergingTaskRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailboxMergingTaskRunner.class);

    private final MailboxManager mailboxManager;
    private final StoreMessageIdManager messageIdManager;
    private final CassandraMessageIdDAO cassandraMessageIdDAO;
    private final CassandraMailboxDAO mailboxDAO;
    private final CassandraUserMailboxRightsDAO rightsDAO;
    private final CassandraACLMapper cassandraACLMapper;

    @Inject
    public MailboxMergingTaskRunner(MailboxManager mailboxManager, StoreMessageIdManager messageIdManager, CassandraMessageIdDAO cassandraMessageIdDAO, CassandraMailboxDAO mailboxDAO, CassandraUserMailboxRightsDAO rightsDAO, CassandraACLMapper cassandraACLMapper) {
        this.mailboxManager = mailboxManager;
        this.messageIdManager = messageIdManager;
        this.cassandraMessageIdDAO = cassandraMessageIdDAO;
        this.mailboxDAO = mailboxDAO;
        this.rightsDAO = rightsDAO;
        this.cassandraACLMapper = cassandraACLMapper;
    }

    public Task.Result run(CassandraId oldMailboxId, CassandraId newMailboxId) throws MailboxException {
        MailboxSession session = mailboxManager.createSystemSession("task");
        return moveMessages(oldMailboxId, newMailboxId, session)
            .onComplete(() -> mergeRights(oldMailboxId, newMailboxId),
                () -> mailboxDAO.delete(oldMailboxId).join());
    }

    private Task.Result moveMessages(CassandraId oldMailboxId, CassandraId newMailboxId, MailboxSession session) {
        return cassandraMessageIdDAO.retrieveMessages(oldMailboxId, MessageRange.all())
            .join()
            .map(ComposedMessageIdWithMetaData::getComposedMessageId)
            .map(messageId -> moveMessage(newMailboxId, messageId, session))
            .reduce(Task.Result.COMPLETED, Task::combine);
    }

    private Task.Result moveMessage(CassandraId newMailboxId, ComposedMessageId composedMessageId, MailboxSession session) {
        try {
            messageIdManager.setInMailboxesNoCheck(composedMessageId.getMessageId(), newMailboxId, session);
            return Task.Result.COMPLETED;
        } catch (MailboxException e) {
            LOGGER.warn("Failed moving message {}", composedMessageId.getMessageId(), e);
            return Task.Result.PARTIAL;
        }
    }

    private void mergeRights(CassandraId oldMailboxId, CassandraId newMailboxId) throws MailboxException {
        MailboxACL oldAcl = cassandraACLMapper.getACL(oldMailboxId).join();
        MailboxACL newAcl = cassandraACLMapper.getACL(newMailboxId).join();
        MailboxACL finalAcl = newAcl.union(oldAcl);

        cassandraACLMapper.setACL(newMailboxId, finalAcl);
        rightsDAO.update(oldMailboxId, ACLDiff.computeDiff(oldAcl, MailboxACL.EMPTY)).join();
    }
}
