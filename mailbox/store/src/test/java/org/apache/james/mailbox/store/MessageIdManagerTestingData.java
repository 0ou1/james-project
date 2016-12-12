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

import javax.mail.Flags;

import org.apache.james.mailbox.MessageIdManager;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.store.mail.model.Mailbox;

public abstract class MessageIdManagerTestingData {
    private final MessageIdManager messageIdManager;
    private final Mailbox mailbox1;
    private final Mailbox mailbox2;

    public MessageIdManagerTestingData(MessageIdManager messageIdManager, Mailbox mailbox1, Mailbox mailbox2) {
        this.messageIdManager = messageIdManager;
        this.mailbox1 = mailbox1;
        this.mailbox2 = mailbox2;
    }

    public MessageIdManager getMessageIdManager() {
        return messageIdManager;
    }

    public Mailbox getMailbox1() {
        return mailbox1;
    }

    public Mailbox getMailbox2() {
        return mailbox2;
    }

    // Should take care of find returning the MailboxMessage
    // Should take care of findMailboxes returning the mailbox the message is in
    // Should persist flags // Should keep track of flag state for setFlags
    public abstract MessageId persist(MailboxId mailboxId, Flags flags);

    public abstract MessageId createNotUsedMessageId();

    public abstract void deleteMailbox(MailboxId mailboxId);

    public abstract void clean();
}
