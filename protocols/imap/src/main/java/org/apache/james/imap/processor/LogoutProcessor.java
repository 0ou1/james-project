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

package org.apache.james.imap.processor;

import java.io.Closeable;

import org.apache.james.imap.api.ImapCommand;
import org.apache.james.imap.api.ImapSessionUtils;
import org.apache.james.imap.api.Tag;
import org.apache.james.imap.api.display.HumanReadableText;
import org.apache.james.imap.api.message.response.StatusResponseFactory;
import org.apache.james.imap.api.process.ImapProcessor;
import org.apache.james.imap.api.process.ImapSession;
import org.apache.james.imap.message.request.LogoutRequest;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.MDCBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogoutProcessor extends AbstractMailboxProcessor<LogoutRequest> {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogoutProcessor.class);

    public LogoutProcessor(ImapProcessor next, MailboxManager mailboxManager, StatusResponseFactory factory,
            MetricFactory metricFactory) {
        super(LogoutRequest.class, next, mailboxManager, factory, metricFactory);
    }

    @Override
    protected void processMessage(LogoutRequest request, ImapSession session, Tag tag, ImapCommand command, Responder responder) {
        final MailboxSession mailboxSession = ImapSessionUtils.getMailboxSession(session);
        try {
            getMailboxManager().logout(mailboxSession, false);
            session.logout();
            bye(responder);
            okComplete(request, responder);
        } catch (MailboxException e) {
            LOGGER.error("Logout failed for user {}", mailboxSession.getUser().asString(), e);
            no(request, responder, HumanReadableText.GENERIC_FAILURE_DURING_PROCESSING);
        }
    }

    @Override
    protected Closeable addContextToMDC(LogoutRequest message) {
        return MDCBuilder.create()
            .addContext(MDCBuilder.ACTION, "LOGOUT")
            .build();
    }
}
