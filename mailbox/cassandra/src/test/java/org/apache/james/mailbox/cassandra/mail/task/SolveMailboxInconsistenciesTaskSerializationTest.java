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

import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;

import org.apache.james.JsonSerializationVerifier;
import org.apache.james.core.Username;
import org.apache.james.mailbox.cassandra.ids.CassandraId;
import org.apache.james.mailbox.model.MailboxConstants;
import org.apache.james.mailbox.model.MailboxPath;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class SolveMailboxInconsistenciesTaskSerializationTest {
    private static final Instant TIMESTAMP = Instant.parse("2018-11-13T12:00:55Z");
    private static final Username USERNAME = Username.of("user");
    private static final MailboxPath MAILBOX_PATH = new MailboxPath(MailboxConstants.USER_NAMESPACE, USERNAME, "mailboxName");
    private static final MailboxPath MAILBOX_PATH_2 = new MailboxPath(MailboxConstants.USER_NAMESPACE, USERNAME, "mailboxName2");
    private static final CassandraId MAILBOX_ID = CassandraId.of(UUID.fromString("464765a0-e4e7-11e4-aba4-710c1de3782b"));

    private static final SolveMailboxInconsistenciesService SERVICE = mock(SolveMailboxInconsistenciesService.class);
    private static final SolveMailboxInconsistenciesTask TASK = new SolveMailboxInconsistenciesTask(SERVICE);
    private static final String SERIALIZED_TASK = "{\"type\": \"solve-mailbox-inconsistencies\"}";
    private static final ConflictingEntry CONFLICTING_ENTRY = new ConflictingEntry(MAILBOX_PATH, MAILBOX_ID, MAILBOX_PATH_2, MAILBOX_ID);
    private static final ImmutableList<ConflictingEntry> CONFLICTING_ENTRIES = ImmutableList.of(CONFLICTING_ENTRY);
    private static final SolveMailboxInconsistenciesTask.Details DETAILS = new SolveMailboxInconsistenciesTask.Details(TIMESTAMP, 0, 1, 2, CONFLICTING_ENTRIES, 3);
    private static final String SERIALIZED_ADDITIONAL_INFORMATION = "{\"type\":\"solve-mailbox-inconsistencies\",\"processedMailboxEntries\":0,\"processedMailboxPathEntries\":1,\"fixedInconsistencies\":2,\"conflictingEntries\":[{\"mailboxPathAsString\":\"#private:user:mailboxName\",\"mailboxIdAsString\":\"464765a0-e4e7-11e4-aba4-710c1de3782b\",\"pathRegistrationPathAsString\":\"#private:user:mailboxName2\",\"pathRegistrationIdAsString\":\"464765a0-e4e7-11e4-aba4-710c1de3782b\"}],\"errors\":3,\"timestamp\":\"2018-11-13T12:00:55Z\"}";

    @Test
    void taskShouldBeSerializable() throws Exception {
        JsonSerializationVerifier.dtoModule(SolveMailboxInconsistenciesTaskDTO.module(SERVICE))
            .bean(TASK)
            .json(SERIALIZED_TASK)
            .verify();
    }

    @Test
    void additionalInformationShouldBeSerializable() throws Exception {
        JsonSerializationVerifier.dtoModule(SolveMailboxInconsistenciesTaskAdditionalInformationDTO.MODULE)
            .bean(DETAILS)
            .json(SERIALIZED_ADDITIONAL_INFORMATION)
            .verify();
    }
}