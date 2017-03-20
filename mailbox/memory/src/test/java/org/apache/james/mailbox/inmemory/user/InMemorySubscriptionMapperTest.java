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

package org.apache.james.mailbox.inmemory.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Before;
import org.junit.Test;

public class InMemorySubscriptionMapperTest {

    private static final String USER_1 = "user1";
    private static final String USER_2 = "user2";
    private static final String MAILBOX_1 = "mailbox1";
    private static final String MAILBOX_2 = "mailbox2";

    private InMemorySubscriptionMapper testee;

    @Before
    public void setUp() {
        testee = new InMemorySubscriptionMapper();
    }

    @Test
    public void findSubscriptionsForUserShouldBeEmptyByDefault() {
        /*
        Question 1

        Check findSubscriptionsForUser return no subscription for USER_1 if we did not create one
         */
    }

    @Test
    public void findMailboxSubscriptionForUserShouldReturnNullByDefault() {
        /*
        Question 2

        Check findMailboxSubscriptionForUser return a null subscription for USER_1 if we did not create one
         */
    }

    @Test
    public void findMailboxSubscriptionForUserShouldReturnSubscription() {
        /*
        Question 3

        Register a subscription for User_1

        Check that findSubscriptionsForUser now return this subscription
         */
    }

    @Test
    public void findSubscriptionsForUserShouldReturnSubscriptions() {
        /*
        Question 4

        Register subscription for User_1 in MAILBOX_1 and MAILBOX_2

        Check that findSubscriptionsForUser now return these subscriptions
         */
    }

    @Test
    public void findSubscriptionsForUserShouldReturnOnlyUserSubscriptions() {
        /*
        Question 5

        Register subscription for User_1 in MAILBOX_1
        Register subscription for User_2 in MAILBOX_1

        Check that findSubscriptionsForUser for USER_1 now return only USER_1 subscription
         */
    }

    @Test
    public void findMailboxSubscriptionForUserShouldReturnOnlyUserSubscriptions() {
        /*
        Question 6

        Register subscription for User_1 in MAILBOX_1
        Register subscription for User_2 in MAILBOX_1

        Check that findMailboxSubscriptionForUser for USER_1, MAILBOX_1 should only return this subscription
         */
    }

    @Test
    public void findMailboxSubscriptionForUserShouldReturnSubscriptionConcerningTheMailbox() {
        /*
        Question 7

        Register subscription for User_1 in MAILBOX_1
        Register subscription for User_1 in MAILBOX_2

        Check that findMailboxSubscriptionForUser for USER_1, MAILBOX_1 should only return this subscription
         */
    }
}
