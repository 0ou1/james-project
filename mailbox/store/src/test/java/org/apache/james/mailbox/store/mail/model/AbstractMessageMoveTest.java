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

package org.apache.james.mailbox.store.mail.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;

import javax.mail.Flags;
import javax.mail.util.SharedByteArrayInputStream;

import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.store.mail.MessageMapper;
import org.apache.james.mailbox.store.mail.MessageMapper.FetchType;
import org.apache.james.mailbox.store.mail.model.impl.PropertyBuilder;
import org.apache.james.mailbox.store.mail.model.impl.SimpleMailbox;
import org.apache.james.mailbox.store.mail.model.impl.SimpleMailboxMessage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public abstract class AbstractMessageMoveTest<Id extends MailboxId> {

    private final static char DELIMITER = ':';
    private static final int LIMIT = 10;
    private static final int BODY_START = 16;
    public static final int UID_VALIDITY = 42;

    private MapperProvider<Id> mapperProvider;
    private MessageMapper<Id> messageMapper;

    private SimpleMailbox<Id> benwaInboxMailbox;
    private SimpleMailbox<Id> benwaWorkMailbox;

    private SimpleMailboxMessage<Id> message1;

    public AbstractMessageMoveTest(MapperProvider<Id> mapperProvider) {
        this.mapperProvider = mapperProvider;
    }

    @Before
    public void setUp() throws MailboxException {
        mapperProvider.ensureMapperPrepared();
        messageMapper = mapperProvider.createMessageMapper();
        benwaInboxMailbox = createMailbox(new MailboxPath("#private", "benwa", "INBOX"));
        benwaWorkMailbox = createMailbox( new MailboxPath("#private", "benwa", "INBOX"+DELIMITER+"work"));
        message1 = createMessage(benwaInboxMailbox, "Subject: Test1 \n\nBody1\n.\n", BODY_START, new PropertyBuilder());
    }

    @After
    public void tearDown() throws MailboxException {
        mapperProvider.clearMapper();
    }

    @Test
    public void movingAMessageShoudlWork() throws Exception {
        messageMapper.add(benwaInboxMailbox, message1);
        message1.setModSeq(messageMapper.getHighestModSeq(benwaInboxMailbox));

        messageMapper.move(benwaWorkMailbox, message1);

        assertThat(retrieveMessageFromStorage(benwaWorkMailbox, message1)).isEqualTo(message1);
    }

    @Test
    public void movingAMessageShoudlReturnCorrectMetadata() throws Exception {
        messageMapper.add(benwaInboxMailbox, message1);
        message1.setModSeq(messageMapper.getHighestModSeq(benwaInboxMailbox));

        MessageMetaData messageMetaData = messageMapper.move(benwaWorkMailbox, message1);

        assertThat(messageMetaData.getFlags()).isEqualTo(message1.createFlags());
        assertThat(messageMetaData.getUid()).isEqualTo(messageMapper.getLastUid(benwaWorkMailbox));
        assertThat(messageMetaData.getModSeq()).isEqualTo(messageMapper.getHighestModSeq(benwaWorkMailbox));
    }

    @Test
    public void movingAMessageShoudlNotViolateMessageCount() throws Exception {
        messageMapper.add(benwaInboxMailbox, message1);
        message1.setModSeq(messageMapper.getHighestModSeq(benwaInboxMailbox));

        messageMapper.move(benwaWorkMailbox, message1);

        assertThat(messageMapper.countMessagesInMailbox(benwaInboxMailbox)).isEqualTo(0);
        assertThat(messageMapper.countMessagesInMailbox(benwaWorkMailbox)).isEqualTo(1);
    }

    @Test
    public void movingAMessageShoudlNotViolateUnseenMessageCount() throws Exception {
        messageMapper.add(benwaInboxMailbox, message1);
        message1.setModSeq(messageMapper.getHighestModSeq(benwaInboxMailbox));

        messageMapper.move(benwaWorkMailbox, message1);

        assertThat(messageMapper.countUnseenMessagesInMailbox(benwaInboxMailbox)).isEqualTo(0);
        assertThat(messageMapper.countUnseenMessagesInMailbox(benwaWorkMailbox)).isEqualTo(1);
    }

    @Test
    public void movingASeenMessageShoudlNotIncrementUnseenMessageCount() throws Exception {
        message1.setFlags(new Flags(Flags.Flag.SEEN));
        messageMapper.add(benwaInboxMailbox, message1);
        message1.setModSeq(messageMapper.getHighestModSeq(benwaInboxMailbox));

        messageMapper.move(benwaWorkMailbox, message1);

        assertThat(messageMapper.countUnseenMessagesInMailbox(benwaInboxMailbox)).isEqualTo(0);
        assertThat(messageMapper.countUnseenMessagesInMailbox(benwaWorkMailbox)).isEqualTo(0);
    }

    private SimpleMailbox<Id> createMailbox(MailboxPath mailboxPath) {
        SimpleMailbox<Id> mailbox = new SimpleMailbox<Id>(mailboxPath, UID_VALIDITY);
        Id id = mapperProvider.generateId();
        mailbox.setMailboxId(id);
        return mailbox;
    }

    private MailboxMessage<Id> retrieveMessageFromStorage(Mailbox<Id> mailbox, MailboxMessage<Id> message) throws MailboxException {
        return messageMapper.findInMailbox(mailbox, MessageRange.one(message.getUid()), FetchType.Metadata, LIMIT).next();
    }
    
    private SimpleMailboxMessage<Id> createMessage(Mailbox<Id> mailbox, String content, int bodyStart, PropertyBuilder propertyBuilder) {
        return new SimpleMailboxMessage<Id>(new Date(), content.length(), bodyStart, new SharedByteArrayInputStream(content.getBytes()), new Flags(), propertyBuilder, mailbox.getMailboxId());
    }
}
