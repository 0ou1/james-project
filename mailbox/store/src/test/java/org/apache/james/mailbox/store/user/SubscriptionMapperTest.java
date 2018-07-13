/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 * *
 * http://www.apache.org/licenses/LICENSE-2.0                 *
 * *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mailbox.store.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.apache.james.mailbox.exception.SubscriptionException;
import org.apache.james.mailbox.store.user.model.Subscription;
import org.apache.james.mailbox.store.user.model.impl.SimpleSubscription;
import org.junit.jupiter.api.Test;

public interface SubscriptionMapperTest {
    String USER_1 = "user1";
    String USER_2 = "user2";
    String MAILBOX_1 = "mailbox1";
    String MAILBOX_2 = "mailbox2";

    SubscriptionMapper testee();

    @Test
    default void findSubscriptionsForUserShouldBeEmptyByDefault() throws SubscriptionException {
        List<Subscription> subscriptions = testee().findSubscriptionsForUser(USER_1);

        assertThat(subscriptions).isEmpty();
    }

    @Test
    default void findMailboxSubscriptionForUserShouldReturnNullByDefault() throws SubscriptionException {
        Subscription subscriptions = testee().findMailboxSubscriptionForUser(USER_1,MAILBOX_1);

        assertThat(subscriptions).isNull();
    }

    @Test
    default void findMailboxSubscriptionForUserShouldReturnSubscription() throws SubscriptionException {
        SimpleSubscription subscription = new SimpleSubscription(USER_1, MAILBOX_1);
        testee().save(subscription);

        List<Subscription> results = testee().findSubscriptionsForUser(USER_1);

        assertThat(results).containsOnly(subscription);
    }

    @Test
    default void findSubscriptionsForUserShouldReturnSubscriptions() throws SubscriptionException {
        SimpleSubscription subscription1 = new SimpleSubscription(USER_1, MAILBOX_1);
        SimpleSubscription subscription2 = new SimpleSubscription(USER_1, MAILBOX_2);
        testee().save(subscription1);
        testee().save(subscription2);

        List<Subscription> results = testee().findSubscriptionsForUser(USER_1);

        assertThat(results).containsOnly(subscription1, subscription2);
    }

    @Test
    default void findSubscriptionsForUserShouldReturnOnlyUserSubscriptions() throws SubscriptionException {
        SimpleSubscription subscription1 = new SimpleSubscription(USER_1,MAILBOX_1);
        SimpleSubscription subscription2 = new SimpleSubscription(USER_2,MAILBOX_2);
        testee().save(subscription1);
        testee().save(subscription2);

        List<Subscription> results = testee().findSubscriptionsForUser(USER_1);

        assertThat(results).containsOnly(subscription1);
    }

    @Test
    default void findMailboxSubscriptionForUserShouldReturnOnlyUserSubscriptions() throws SubscriptionException {
        SimpleSubscription subscription1 = new SimpleSubscription(USER_1,MAILBOX_1);
        SimpleSubscription subscription2 = new SimpleSubscription(USER_2,MAILBOX_1);
        testee().save(subscription1);
        testee().save(subscription2);

        Subscription result = testee().findMailboxSubscriptionForUser(USER_1,MAILBOX_1);

        assertThat(result).isEqualTo(result);
    }

    @Test
    default void findMailboxSubscriptionForUserShouldReturnSubscriptionConcerningTheMailbox() throws SubscriptionException {
        SimpleSubscription subscription1 = new SimpleSubscription(USER_1,MAILBOX_1);
        SimpleSubscription subscription2 = new SimpleSubscription(USER_1,MAILBOX_2);
        testee().save(subscription1);
        testee().save(subscription2);

        Subscription result = testee().findMailboxSubscriptionForUser(USER_1,MAILBOX_1);

        assertThat(result).isEqualTo(result);
    }

    @Test
    default void deleteShouldRemoveSubscription() throws SubscriptionException {
        SimpleSubscription subscription = new SimpleSubscription(USER_1, MAILBOX_1);
        testee().save(subscription);

        testee().delete(subscription);

        assertThat(testee().findSubscriptionsForUser(USER_1)).isEmpty();
    }
}
