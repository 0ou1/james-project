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

package org.apache.james.mailrepository.cassandra;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.mailrepository.api.MailKey;
import org.apache.james.mailrepository.api.MailRepositoryUrl;
import org.junit.jupiter.api.Test;

public interface CassandraMailRepositoryKeysDAOTest {
    MailRepositoryUrl URL = MailRepositoryUrl.from("proto://url");
    MailRepositoryUrl URL2 = MailRepositoryUrl.from("proto://url2");
    MailKey KEY_1 = new MailKey("key1");
    MailKey KEY_2 = new MailKey("key2");
    MailKey KEY_3 = new MailKey("key3");

    CassandraMailRepositoryKeysDAO testee();

    @Test
    default void test() {
        assertThat(testee().list(URL).join())
            .isEmpty();
    }

    @Test
    default void listShouldReturnEmptyByDefault() {
        testee().store(URL, KEY_1).join();

        assertThat(testee().list(URL).join())
            .containsOnly(KEY_1);
    }

    @Test
    default void listShouldNotReturnElementsOfOtherRepositories() {
        testee().store(URL, KEY_1).join();

        assertThat(testee().list(URL2).join())
            .isEmpty();
    }

    @Test
    default void listShouldReturnSeveralElements() {
        testee().store(URL, KEY_1).join();
        testee().store(URL, KEY_2).join();
        testee().store(URL, KEY_3).join();

        assertThat(testee().list(URL).join())
            .containsOnly(KEY_1, KEY_2, KEY_3);
    }

    @Test
    default void listShouldNotReturnRemovedElements() {
        testee().store(URL, KEY_1).join();
        testee().store(URL, KEY_2).join();
        testee().store(URL, KEY_3).join();

        testee().remove(URL, KEY_2).join();

        assertThat(testee().list(URL).join())
            .containsOnly(KEY_1, KEY_3);
    }

    @Test
    default void removeShouldBeIdempotent() {
        testee().remove(URL, KEY_2).join();
    }

    @Test
    default void removeShouldNotAffectOtherRepositories() {
        testee().store(URL, KEY_1).join();

        testee().remove(URL2, KEY_2).join();

        assertThat(testee().list(URL).join())
            .containsOnly(KEY_1);
    }

}