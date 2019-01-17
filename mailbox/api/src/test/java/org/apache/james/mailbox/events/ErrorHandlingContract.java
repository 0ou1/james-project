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

package org.apache.james.mailbox.events;

import static org.apache.james.mailbox.events.EventBusTestFixture.EVENT;
import static org.apache.james.mailbox.events.EventBusTestFixture.GROUP_A;
import static org.apache.james.mailbox.events.EventBusTestFixture.KEY_1;
import static org.apache.james.mailbox.events.EventBusTestFixture.NO_KEYS;
import static org.apache.james.mailbox.events.EventBusTestFixture.WAIT_CONDITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.james.mailbox.Event;
import org.apache.james.mailbox.MailboxListener;
import org.apache.james.mailbox.util.EventCollector;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

interface ErrorHandlingContract extends EventBusContract {

    class ThrowingListener implements MailboxListener {
        private final List<Instant> timeElapsed;

        private ThrowingListener() {
            timeElapsed = new ArrayList<>();
        }

        @Override
        public void event(Event event) {
            timeElapsed.add(Instant.now());
            throw new RuntimeException("throw to trigger reactor retry");
        }
    }

    EventDeadLetters deadLetter();

    default EventCollector eventCollector() {
        return spy(new EventCollector());
    }

    default ThrowingListener throwingListener() {
        return new ThrowingListener();
    }

    @Test
    default void retryingIsNotAppliedForKeyRegistrations() {
        EventCollector eventCollector = eventCollector();

        doThrow(new RuntimeException())
            .when(eventCollector).event(EVENT);

        eventBus().register(eventCollector, KEY_1);
        eventBus().dispatch(EVENT, ImmutableSet.of(KEY_1)).block();

        assertThat(eventCollector.getEvents())
            .isEmpty();
    }

    @Test
    default void deadLettersIsNotAppliedForKeyRegistrations() throws Exception {
        EventCollector eventCollector = eventCollector();

        doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doCallRealMethod()
            .when(eventCollector).event(EVENT);

        eventBus().register(eventCollector, KEY_1);
        eventBus().dispatch(EVENT, ImmutableSet.of(KEY_1)).block();

        TimeUnit.SECONDS.sleep(1);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(eventCollector.getEvents()).isEmpty();
            softly.assertThat(deadLetter().failedEventIds(GROUP_A).toIterable())
                .isEmpty();
        });
    }

    @Test
    default void listenerShouldReceiveWhenFailsLessThanMaxRetries() {
        EventCollector eventCollector = eventCollector();

        doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doCallRealMethod()
            .when(eventCollector).event(EVENT);

        eventBus().register(eventCollector, new EventBusTestFixture.GroupA());
        eventBus().dispatch(EVENT, NO_KEYS).block();

        WAIT_CONDITION
            .until(() -> assertThat(eventCollector.getEvents()).hasSize(1));
    }

    @Test
    default void listenerShouldReceiveWhenFailsEqualsMaxRetries() {
        EventCollector eventCollector = eventCollector();

        doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doCallRealMethod()
            .when(eventCollector).event(EVENT);

        eventBus().register(eventCollector, new EventBusTestFixture.GroupA());
        eventBus().dispatch(EVENT, NO_KEYS).block();

        WAIT_CONDITION
            .until(() -> assertThat(eventCollector.getEvents()).hasSize(1));
    }

    @Test
    default void listenerShouldNotReceiveWhenFailsGreaterThanMaxRetries() throws Exception {
        EventCollector eventCollector = eventCollector();

        doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doCallRealMethod()
            .when(eventCollector).event(EVENT);

        eventBus().register(eventCollector, new EventBusTestFixture.GroupA());
        eventBus().dispatch(EVENT, NO_KEYS).block();

        TimeUnit.SECONDS.sleep(1);
        assertThat(eventCollector.getEvents())
            .isEmpty();
    }

    @Test
    default void exceedingMaxRetriesShouldStopConsumingFailedEvent() throws Exception {
        ThrowingListener throwingListener = throwingListener();

        eventBus().register(throwingListener, new EventBusTestFixture.GroupA());
        eventBus().dispatch(EVENT, NO_KEYS).block();

        TimeUnit.SECONDS.sleep(5);
        int numberOfCallsAfterExceedMaxRetries = throwingListener.timeElapsed.size();
        TimeUnit.SECONDS.sleep(5);

        assertThat(throwingListener.timeElapsed.size())
            .isEqualTo(numberOfCallsAfterExceedMaxRetries);
    }

    @Test
    default void retriesBackOffShouldDelayByExponentialGrowth() throws Exception {
        ThrowingListener throwingListener = throwingListener();

        eventBus().register(throwingListener, new EventBusTestFixture.GroupA());
        eventBus().dispatch(EVENT, NO_KEYS).block();

        TimeUnit.SECONDS.sleep(5);
        SoftAssertions.assertSoftly(softly -> {
            List<Instant> timeElapsed = throwingListener.timeElapsed;
            softly.assertThat(timeElapsed).hasSize(4);

            long minFirstDelayAfter = 100; // first backOff
            long minSecondDelayAfter = 100; // 200 * jitter factor (200 * 0.5)
            long minThirdDelayAfter = 200; // 400 * jitter factor (400 * 0.5)

            softly.assertThat(timeElapsed.get(1))
                .isAfterOrEqualTo(timeElapsed.get(0).plusMillis(minFirstDelayAfter));

            softly.assertThat(timeElapsed.get(2))
                .isAfterOrEqualTo(timeElapsed.get(1).plusMillis(minSecondDelayAfter));

            softly.assertThat(timeElapsed.get(3))
                .isAfterOrEqualTo(timeElapsed.get(2).plusMillis(minThirdDelayAfter));
        });
    }

    @Test
    default void deadLetterShouldNotStoreWhenFailsLessThanMaxRetries() {
        EventCollector eventCollector = eventCollector();

        doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doCallRealMethod()
            .when(eventCollector).event(EVENT);

        eventBus().register(eventCollector, new EventBusTestFixture.GroupA());
        eventBus().dispatch(EVENT, NO_KEYS).block();

        WAIT_CONDITION
            .until(() -> assertThat(eventCollector.getEvents()).hasSize(1));

        assertThat(deadLetter().groupsWithFailedEvents().toIterable())
            .isEmpty();
    }

    @Test
    default void deadLetterShouldStoreWhenFailsGreaterThanMaxRetries() throws Exception {
        EventCollector eventCollector = eventCollector();

        doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doThrow(new RuntimeException())
            .doCallRealMethod()
            .when(eventCollector).event(EVENT);

        eventBus().register(eventCollector, GROUP_A);
        eventBus().dispatch(EVENT, NO_KEYS).block();

        TimeUnit.SECONDS.sleep(1);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(eventCollector.getEvents()).isEmpty();
            softly.assertThat(deadLetter().failedEventIds(GROUP_A).toIterable())
                .containsOnly(EVENT.getEventId());
        });
    }
}
