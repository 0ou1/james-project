/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 * http://www.apache.org/licenses/LICENSE-2.0                   *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mailbox.cassandra.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.store.mail.model.Mailbox;
import org.apache.james.mailbox.store.mail.model.impl.SimpleMailbox;
import org.apache.james.util.concurrency.ConcurrentTestRunner;
import org.junit.jupiter.api.Test;

public interface CassandraMailboxMapperConcurrencyTest {

    int UID_VALIDITY = 52;
    MailboxPath MAILBOX_PATH = MailboxPath.forUser("user", "name");
    int THREAD_COUNT = 10;
    int OPERATION_COUNT = 10;

    CassandraMailboxMapper testee();

    @Test
    default void saveShouldBeThreadSafe() throws Exception {
        boolean termination = ConcurrentTestRunner.builder()
            .threadCount(THREAD_COUNT)
            .operationCount(OPERATION_COUNT)
            .build((a, b) -> testee().save(new SimpleMailbox(MAILBOX_PATH, UID_VALIDITY)))
            .run()
            .awaitTermination(1, TimeUnit.MINUTES);

        assertThat(termination).isTrue();
        assertThat(testee().list()).hasSize(1);
    }

    @Test
    default void saveWithUpdateShouldBeThreadSafe() throws Exception {
        SimpleMailbox mailbox = new SimpleMailbox(MAILBOX_PATH, UID_VALIDITY);
        testee().save(mailbox);

        mailbox.setName("newName");

        boolean termination = ConcurrentTestRunner.builder()
            .threadCount(THREAD_COUNT)
            .operationCount(OPERATION_COUNT)
            .build((a, b) -> testee().save(mailbox))
            .run()
            .awaitTermination(1, TimeUnit.MINUTES);

        assertThat(termination).isTrue();
        List<Mailbox> list = testee().list();
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).isEqualToComparingFieldByField(mailbox);
    }
}
