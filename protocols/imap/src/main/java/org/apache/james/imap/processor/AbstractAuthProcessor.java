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

import java.util.Optional;

import org.apache.james.imap.api.ImapCommand;
import org.apache.james.imap.api.ImapSessionUtils;
import org.apache.james.imap.api.display.HumanReadableText;
import org.apache.james.imap.api.message.request.ImapRequest;
import org.apache.james.imap.api.message.response.StatusResponseFactory;
import org.apache.james.imap.api.process.ImapProcessor;
import org.apache.james.imap.api.process.ImapSession;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.exception.BadCredentialsException;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.MailboxExistsException;
import org.apache.james.mailbox.exception.NotAdminException;
import org.apache.james.mailbox.exception.UserDoesNotExistException;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.OptionalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.github.fge.lambdas.predicates.PredicateChainer;
import com.google.common.base.Preconditions;

public abstract class AbstractAuthProcessor<M extends ImapRequest> extends AbstractMailboxProcessor<M> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractAuthProcessor.class);

    private static final String ATTRIBUTE_NUMBER_OF_FAILURES = "org.apache.james.imap.processor.imap4rev1.NUMBER_OF_FAILURES";

    // TODO: this should be configurable
    private static final int MAX_FAILURES = 3;
    
    public AbstractAuthProcessor(Class<M> acceptableClass, ImapProcessor next, MailboxManager mailboxManager, StatusResponseFactory factory,
            MetricFactory metricFactory) {
        super(acceptableClass, next, mailboxManager, factory, metricFactory);
    }

    protected void doAuth(AuthenticationAttempt authenticationAttempt, ImapSession session, String tag, ImapCommand command, Responder responder, HumanReadableText failed) {
        Preconditions.checkArgument(!authenticationAttempt.isDelegation());
        PredicateChainer<ValidatedAuthenticationAttempt> authenticationMethod = Throwing.predicate(attempt -> login(attempt, session));
        try {
            authenticationProcess(authenticationAttempt, session, tag, command, responder, failed, authenticationMethod);
        } catch (Exception e) {
            LOGGER.error("Error encountered while login", e);
            no(command, tag, responder, HumanReadableText.GENERIC_FAILURE_DURING_PROCESSING);
        }
    }

    protected void doAuthWithDelegation(AuthenticationAttempt authenticationAttempt, ImapSession session, String tag, ImapCommand command, Responder responder, HumanReadableText failed) {
        Preconditions.checkArgument(authenticationAttempt.isDelegation());
        PredicateChainer<ValidatedAuthenticationAttempt> authenticationMethod = Throwing.predicate(attempt -> loginAsOtherUser(attempt, session));
        try {
            authenticationProcess(authenticationAttempt, session, tag, command, responder, failed, authenticationMethod);
        } catch (UserDoesNotExistException e) {
            LOGGER.info("User {} does not exist", authenticationAttempt.getAuthenticationId(), e);
            no(command, tag, responder, HumanReadableText.USER_DOES_NOT_EXIST);
        } catch (NotAdminException e) {
            LOGGER.info("User {} is not an admin", authenticationAttempt.getDelegateUserName(), e);
            no(command, tag, responder, HumanReadableText.NOT_AN_ADMIN);
        } catch (MailboxException e) {
            LOGGER.info("Login failed", e);
            no(command, tag, responder, HumanReadableText.GENERIC_FAILURE_DURING_PROCESSING);
        }
    }

    private void authenticationProcess(AuthenticationAttempt authenticationAttempt, ImapSession session, String tag, ImapCommand command, Responder responder, HumanReadableText failed, PredicateChainer<ValidatedAuthenticationAttempt> authenticationMethod) throws MailboxException {
        boolean authFailure = authenticationAttempt.validate()
            .filter(authenticationMethod.sneakyThrow())
            .isPresent();

        if (authFailure) {
            manageFailureCount(session, tag, command, responder, failed);
        } else {
            okComplete(command, tag, responder);
        }
    }

    private boolean login(ValidatedAuthenticationAttempt authenticationAttempt, ImapSession session) throws MailboxException {
        MailboxManager mailboxManager = getMailboxManager();
        try {
            MailboxSession mailboxSession = mailboxManager.login(authenticationAttempt.getAuthenticationId(),
                authenticationAttempt.getPassword());
            session.authenticated();
            session.setAttribute(ImapSessionUtils.MAILBOX_SESSION_ATTRIBUTE_SESSION_KEY, mailboxSession);
            provisionInbox(mailboxManager, mailboxSession);
            return false;
        } catch (BadCredentialsException e) {
            return true;
        }
    }

    private boolean loginAsOtherUser(ValidatedAuthenticationAttempt authenticationAttempt, ImapSession session) throws MailboxException {
        MailboxManager mailboxManager = getMailboxManager();
        try {
            MailboxSession mailboxSession = mailboxManager.loginAsOtherUser(authenticationAttempt.getAuthenticationId(),
                authenticationAttempt.getPassword(),
                authenticationAttempt.getDelegateUserName().get());
            session.authenticated();
            session.setAttribute(ImapSessionUtils.MAILBOX_SESSION_ATTRIBUTE_SESSION_KEY, mailboxSession);
            provisionInbox(mailboxManager, mailboxSession);
            return false;
        } catch (BadCredentialsException e) {
            return true;
        }
    }

    private void provisionInbox(MailboxManager mailboxManager, MailboxSession mailboxSession) throws MailboxException {
        MailboxPath inboxPath = MailboxPath.inbox(mailboxSession);
        if (mailboxManager.mailboxExists(inboxPath, mailboxSession)) {
            LOGGER.debug("INBOX exists. No need to create it.");
        } else {
            try {
                Optional<MailboxId> mailboxId = mailboxManager.createMailbox(inboxPath, mailboxSession);
                OptionalUtils.executeIfEmpty(mailboxId, () -> LOGGER.warn("Provisioning INBOX successful. But no MailboxId have been returned."))
                    .ifPresent(id -> LOGGER.info("Provisioning INBOX. {} created.", id));
            } catch (MailboxExistsException e) {
                LOGGER.warn("Mailbox INBOX created by concurrent call. Safe to ignore this exception.");
            }
        }
    }

    protected void manageFailureCount(ImapSession session, String tag, ImapCommand command, Responder responder, HumanReadableText failed) {
        int failures = Optional.ofNullable((Integer) session.getAttribute(ATTRIBUTE_NUMBER_OF_FAILURES))
            .orElse(0) + 1;
        if (failures < MAX_FAILURES) {
            session.setAttribute(ATTRIBUTE_NUMBER_OF_FAILURES, failures);
            no(command, tag, responder, failed);
        } else {
            LOGGER.info("Too many authentication failures. Closing connection.");
            bye(responder, HumanReadableText.TOO_MANY_FAILURES);
            session.logout();
        }
    }

    protected static AuthenticationAttempt delegation(String authorizeId, String authenticationId, String password) {
        return new AuthenticationAttempt(Optional.of(authorizeId), authenticationId, password);
    }

    protected static AuthenticationAttempt noDelegation(String authenticationId, String password) {
        return new AuthenticationAttempt(Optional.empty(), authenticationId, password);
    }

    protected static class AuthenticationAttempt {
        private final Optional<String> delegateUserName;
        private final Optional<String> authenticationId;
        private final String password;

        protected AuthenticationAttempt(Optional<String> delegateUserName, String authenticationId, String password) {
            this.delegateUserName = delegateUserName;
            this.authenticationId = Optional.ofNullable(authenticationId);
            this.password = password;
        }

        boolean isDelegation() {
            return delegateUserName.isPresent() && !delegateUserName.get().equals(authenticationId.orElse(null));
        }

        Optional<String> getDelegateUserName() {
            return delegateUserName;
        }

        Optional<String> getAuthenticationId() {
            return authenticationId;
        }

        Optional<ValidatedAuthenticationAttempt> validate() {
            return authenticationId.map(value -> new ValidatedAuthenticationAttempt(delegateUserName, value, password));
        }

        public String getPassword() {
            return password;
        }
    }

    private static class ValidatedAuthenticationAttempt {
        private final Optional<String> delegateUserName;
        private final String authenticationId;
        private final String password;

        ValidatedAuthenticationAttempt(Optional<String> delegateUserName, String authenticationId, String password) {
            this.delegateUserName = delegateUserName;
            this.authenticationId = authenticationId;
            this.password = password;
        }

        private Optional<String> getDelegateUserName() {
            return delegateUserName;
        }

        private String getAuthenticationId() {
            return authenticationId;
        }

        private String getPassword() {
            return password;
        }
    }
}
