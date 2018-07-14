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

package org.apache.james.mailbox.cassandra.event.distributed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.apache.james.backends.cassandra.CassandraCluster;
import org.apache.james.backends.cassandra.utils.CassandraUtils;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.store.publisher.Topic;
import org.junit.jupiter.api.Test;

public interface CassandraMailboxPathRegistrerMapperTest {
    MailboxPath MAILBOX_PATH = new MailboxPath("namespace", "user", "name");
    MailboxPath MAILBOX_PATH_2 = new MailboxPath("namespace2", "user2", "name2");
    Topic TOPIC = new Topic("topic");
    int CASSANDRA_TIME_OUT_IN_S = 100;
    Topic TOPIC_2 = new Topic("topic2");

    CassandraCluster cassandra();

    CassandraMailboxPathRegisterMapper testee();

    @Test
    default void getTopicsShouldReturnEmptyResultByDefault() {
        assertThat(testee().getTopics(MAILBOX_PATH)).isEmpty();
    }

    @Test
    default void doRegisterShouldWork() {
        testee().doRegister(MAILBOX_PATH, TOPIC);
        assertThat(testee().getTopics(MAILBOX_PATH)).containsOnly(TOPIC);
    }

    @Test
    default void doRegisterShouldBeMailboxPathSpecific() {
        testee().doRegister(MAILBOX_PATH, TOPIC);
        assertThat(testee().getTopics(MAILBOX_PATH_2)).isEmpty();
    }

    @Test
    default void doRegisterShouldAllowMultipleTopics() {
        testee().doRegister(MAILBOX_PATH, TOPIC);
        testee().doRegister(MAILBOX_PATH, TOPIC_2);
        assertThat(testee().getTopics(MAILBOX_PATH)).containsOnly(TOPIC, TOPIC_2);
    }

    @Test
    default void doUnRegisterShouldWork() {
        testee().doRegister(MAILBOX_PATH, TOPIC);
        testee().doUnRegister(MAILBOX_PATH, TOPIC);
        assertThat(testee().getTopics(MAILBOX_PATH)).isEmpty();
    }

    @Test
    default void doUnregisterShouldBeMailboxSpecific() {
        testee().doRegister(MAILBOX_PATH, TOPIC);
        testee().doUnRegister(MAILBOX_PATH_2, TOPIC);
        assertThat(testee().getTopics(MAILBOX_PATH)).containsOnly(TOPIC);
    }

    @Test
    default void doUnregisterShouldBeTopicSpecific() {
        testee().doRegister(MAILBOX_PATH, TOPIC);
        testee().doUnRegister(MAILBOX_PATH, TOPIC_2);
        assertThat(testee().getTopics(MAILBOX_PATH)).containsOnly(TOPIC);
    }

    @Test
    default void entriesShouldExpire() throws Exception {
        int verySmallTimeoutInSecond = 1;
        CassandraMailboxPathRegisterMapper testee = new CassandraMailboxPathRegisterMapper(cassandra().getConf(),
            cassandra().getTypesProvider(),
            CassandraUtils.WITH_DEFAULT_CONFIGURATION,
            verySmallTimeoutInSecond);
        testee.doRegister(MAILBOX_PATH, TOPIC);
        Thread.sleep(2 * TimeUnit.SECONDS.toMillis(verySmallTimeoutInSecond));
        assertThat(testee.getTopics(MAILBOX_PATH)).isEmpty();
    }

}
