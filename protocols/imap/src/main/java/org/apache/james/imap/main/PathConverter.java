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

package org.apache.james.imap.main;

import java.util.List;

import org.apache.james.imap.api.ImapSessionUtils;
import org.apache.james.imap.api.process.ImapSession;
import org.apache.james.mailbox.exception.MailboxNotFoundException;
import org.apache.james.mailbox.model.MailboxConstants;
import org.apache.james.mailbox.model.MailboxPath;

import com.github.steveash.guavate.Guavate;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

public class PathConverter {

    private static final int BASE_PART = 0;
    private static final int USER_PART = 1;

    public static PathConverter forSession(ImapSession session) {
        return new PathConverter(session);
    }

    private final char pathDelimiter;
    private final ImapSession.NamespaceConfiguration namespaceConfiguration;
    private final String userName;

    private PathConverter(ImapSession session) {
        pathDelimiter = ImapSessionUtils.getMailboxSession(session).getPathDelimiter();
        namespaceConfiguration = session.getNamespaceConfiguration();
        userName = ImapSessionUtils.getUserName(session);
    }

    public MailboxPath buildFullPath(String mailboxName) throws MailboxNotFoundException {
        Preconditions.checkNotNull(mailboxName);
        List<String> mailboxNameParts = Splitter.on(pathDelimiter)
            .splitToList(mailboxName);
        if (isADelegatedMailboxName(mailboxNameParts)) {
            return buildDelegatedMailboxPath(mailboxNameParts);
        }
        return buildPersonalMailboxPath(mailboxName);
    }

    private boolean isADelegatedMailboxName(List<String> mailboxNameParts) {
        return mailboxNameParts.size() > 2
            && mailboxNameParts.get(BASE_PART).equals(namespaceConfiguration.otherUsersNamespace());
    }

    private MailboxPath buildDelegatedMailboxPath(List<String> mailboxNameParts) {
        return new MailboxPath(MailboxConstants.USER_NAMESPACE,
            addDomainPartToAmbigusUserName(mailboxNameParts.get(USER_PART)),
            sanitizeMailboxName(
                Joiner.on(pathDelimiter)
                    .skipNulls()
                    .join(Iterables.skip(mailboxNameParts, 2))));
    }

    private String addDomainPartToAmbigusUserName(String otherUserName) {
        if (!otherUserName.contains("@")) {
            return otherUserName + locateDomain(userName);
        }
        return otherUserName;
    }

    private String locateDomain(String name) {
        int addressPartSeparator = name.indexOf('@');
        if (addressPartSeparator >= 0) {
            return name.substring(addressPartSeparator, name.length());
        }
        return "";
    }

    private MailboxPath buildPersonalMailboxPath(String mailboxName) throws MailboxNotFoundException {
        if (mailboxName.startsWith(namespaceConfiguration.personalNamespace())) {
            return new MailboxPath(MailboxConstants.USER_NAMESPACE,
                userName,
                sanitizeMailboxName(mailboxName.substring(namespaceConfiguration.personalNamespace().length())));
        }
        throw new MailboxNotFoundException(mailboxName);
    }

    public String buildMailboxName(MailboxPath mailboxPath) {
        Preconditions.checkNotNull(mailboxPath);
        if (userName.equals(mailboxPath.getUser())) {
            return joinMailboxNameParts(
                ImmutableList.of(
                    namespaceConfiguration.personalNamespace(),
                    mailboxPath.getName()));
        }
        return joinMailboxNameParts(
            ImmutableList.of(
                namespaceConfiguration.otherUsersNamespace(),
                sanitizeUserName(mailboxPath.getUser()),
                mailboxPath.getName()));
    }

    private String sanitizeUserName(String name) {
        int addressPartSeparator = name.indexOf('@');
        if (addressPartSeparator >= 0) {
            return name.substring(0, addressPartSeparator);
        }
        return name;
    }

    private String joinMailboxNameParts(ImmutableList<String> mailboxNameParts) {
        return Joiner.on(pathDelimiter)
            .join(mailboxNameParts
                .stream()
                .filter(s -> !Strings.isNullOrEmpty(s))
                .collect(Guavate.toImmutableList()));
    }

    private String sanitizeMailboxName(String mailboxName) {
        // use uppercase for INBOX
        // See IMAP-349
        if (mailboxName.equalsIgnoreCase(MailboxConstants.INBOX)) {
            return MailboxConstants.INBOX;
        }
        return removeRedundantPathDelimiters(mailboxName);
    }

    private String removeRedundantPathDelimiters(String name) {
        Iterable<String> parts = Splitter.on(pathDelimiter)
            .omitEmptyStrings()
            .split(name);
        return Joiner.on(pathDelimiter)
            .join(parts);
    }
}
