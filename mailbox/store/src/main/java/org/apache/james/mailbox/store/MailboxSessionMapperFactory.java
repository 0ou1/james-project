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

import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.RequestAware;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.store.mail.AnnotationMapper;
import org.apache.james.mailbox.store.mail.MailboxMapper;
import org.apache.james.mailbox.store.mail.MailboxMapperFactory;
import org.apache.james.mailbox.store.mail.MessageIdMapper;
import org.apache.james.mailbox.store.mail.MessageMapper;
import org.apache.james.mailbox.store.mail.MessageMapperFactory;
import org.apache.james.mailbox.store.mail.ModSeqProvider;
import org.apache.james.mailbox.store.mail.UidProvider;
import org.apache.james.mailbox.store.transaction.Mapper;
import org.apache.james.mailbox.store.user.SubscriptionMapper;
import org.apache.james.mailbox.store.user.SubscriptionMapperFactory;

/**
 * Maintain mapper instances by {@link MailboxSession}. So only one mapper instance is used
 * in a {@link MailboxSession}
 */
public abstract class MailboxSessionMapperFactory implements RequestAware, MailboxMapperFactory, MessageMapperFactory, SubscriptionMapperFactory {

    protected static final String MESSAGEMAPPER = "MESSAGEMAPPER";
    protected static final String MESSAGEIDMAPPER = "MESSAGEIDMAPPER";
    protected static final String MAILBOXMAPPER = "MAILBOXMAPPER";
    protected static final String SUBSCRIPTIONMAPPER = "SUBSCRIPTIONMAPPER";
    protected static final String ANNOTATIONMAPPER = "ANNOTATIONMAPPER";

    @Deprecated
    public MessageIdMapper getMessageIdMapper(MailboxSession session) throws MailboxException {
        return getMessageIdMapper();
    }

    public AnnotationMapper getAnnotationMapper(MailboxSession session) throws MailboxException {
        AnnotationMapper mapper = (AnnotationMapper)session.getAttributes().get(ANNOTATIONMAPPER);
        if (mapper == null) {
            mapper = createAnnotationMapper(session);
            session.getAttributes().put(ANNOTATIONMAPPER, mapper);
        }
        return mapper;
    }

    public abstract AnnotationMapper createAnnotationMapper(MailboxSession session) throws MailboxException;

    public abstract MessageIdMapper getMessageIdMapper() throws MailboxException;

    public abstract UidProvider getUidProvider();

    public abstract ModSeqProvider getModSeqProvider();

    /**
     * Call endRequest on {@link Mapper} instances
     * 
     * @param session
     */
    @Override
    public void endProcessingRequest(MailboxSession session) {
        if (session == null) {
            return;
        }
        MessageMapper messageMapper = (MessageMapper) session.getAttributes().get(MESSAGEMAPPER);
        MailboxMapper mailboxMapper = (MailboxMapper) session.getAttributes().get(MAILBOXMAPPER);
        SubscriptionMapper subscriptionMapper = (SubscriptionMapper) session.getAttributes().get(SUBSCRIPTIONMAPPER);
        if (messageMapper != null) {
            messageMapper.endRequest();
        }
        if (mailboxMapper != null) {
            mailboxMapper.endRequest();
        }
        if (subscriptionMapper != null) {
            subscriptionMapper.endRequest();
        }
    }

    @Override
    public void startProcessingRequest(MailboxSession session) {
        // Do nothing
        
    }

    
}
