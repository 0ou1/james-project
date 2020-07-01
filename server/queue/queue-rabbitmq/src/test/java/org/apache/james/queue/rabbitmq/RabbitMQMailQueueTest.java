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

package org.apache.james.queue.rabbitmq;

import static java.time.temporal.ChronoUnit.HOURS;
import static org.apache.james.backends.cassandra.Scenario.Builder.fail;
import static org.apache.james.queue.api.Mails.defaultMail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.james.backends.cassandra.CassandraCluster;
import org.apache.james.backends.cassandra.CassandraClusterExtension;
import org.apache.james.backends.cassandra.components.CassandraModule;
import org.apache.james.backends.cassandra.versions.CassandraSchemaVersionModule;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.blob.api.HashBlobId;
import org.apache.james.blob.cassandra.CassandraBlobModule;
import org.apache.james.blob.cassandra.CassandraBlobStore;
import org.apache.james.blob.mail.MimeMessageStore;
import org.apache.james.eventsourcing.eventstore.cassandra.CassandraEventStoreModule;
import org.apache.james.metrics.api.Gauge;
import org.apache.james.queue.api.MailQueue;
import org.apache.james.queue.api.MailQueueMetricContract;
import org.apache.james.queue.api.MailQueueMetricExtension;
import org.apache.james.queue.api.ManageableMailQueue;
import org.apache.james.queue.api.ManageableMailQueueContract;
import org.apache.james.queue.api.RawMailQueueItemDecoratorFactory;
import org.apache.james.queue.rabbitmq.view.RabbitMQMailQueueConfiguration;
import org.apache.james.queue.rabbitmq.view.api.MailQueueView;
import org.apache.james.queue.rabbitmq.view.cassandra.CassandraMailQueueViewModule;
import org.apache.james.queue.rabbitmq.view.cassandra.CassandraMailQueueViewTestFactory;
import org.apache.james.queue.rabbitmq.view.cassandra.configuration.CassandraMailQueueViewConfiguration;
import org.apache.james.util.streams.Iterators;
import org.apache.james.utils.UpdatableTickingClock;
import org.apache.mailet.Mail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

import com.github.fge.lambdas.Throwing;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RabbitMQMailQueueTest {
    private static final HashBlobId.Factory BLOB_ID_FACTORY = new HashBlobId.Factory();
    private static final int THREE_BUCKET_COUNT = 3;
    private static final int UPDATE_BROWSE_START_PACE = 2;
    private static final Duration ONE_HOUR_SLICE_WINDOW = Duration.ofHours(1);
    private static final org.apache.james.queue.api.MailQueueName SPOOL = org.apache.james.queue.api.MailQueueName.of("spool");
    private static final Instant IN_SLICE_1 = Instant.parse("2007-12-03T10:15:30.00Z");
    private static final Instant IN_SLICE_2 = IN_SLICE_1.plus(1, HOURS);
    private static final Instant IN_SLICE_3 = IN_SLICE_1.plus(2, HOURS);
    private static final Instant IN_SLICE_5 = IN_SLICE_1.plus(4, HOURS);
    private static final Instant IN_SLICE_7 = IN_SLICE_1.plus(6, HOURS);

    @RegisterExtension
    static CassandraClusterExtension cassandraCluster = new CassandraClusterExtension(CassandraModule.aggregateModules(
        CassandraBlobModule.MODULE,
        CassandraMailQueueViewModule.MODULE,
        CassandraEventStoreModule.MODULE(),
        CassandraSchemaVersionModule.MODULE));

    @RegisterExtension
    static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ();

    private RabbitMQMailQueueFactory mailQueueFactory;
    private UpdatableTickingClock clock;
    private RabbitMQMailQueue mailQueue;
    private RabbitMQMailQueueManagement mqManagementApi;

    @AfterEach
    void tearDown() {
        mqManagementApi.deleteAllQueues();
    }

    @Nested
    class MailQueueSizeMetricsEnabled implements ManageableMailQueueContract, MailQueueMetricContract {
        @BeforeEach
        void setup(CassandraCluster cassandra,
                   MailQueueMetricExtension.MailQueueMetricTestSystem metricTestSystem) throws Exception {
            setUp(cassandra,
                metricTestSystem,
                RabbitMQMailQueueConfiguration.builder()
                    .sizeMetricsEnabled(true)
                    .build());
        }

        @Override
        public void enQueue(Mail mail) throws MailQueue.MailQueueException {
            ManageableMailQueueContract.super.enQueue(mail);
            clock.tick();
        }

        @Override
        public MailQueue getMailQueue() {
            return mailQueue;
        }

        @Override
        public ManageableMailQueue getManageableMailQueue() {
            return mailQueue;
        }

        @Test
        void browseShouldReturnCurrentlyEnqueuedMailFromAllSlices() throws Exception {
            ManageableMailQueue mailQueue = getManageableMailQueue();
            int emailCount = 5;

            clock.setInstant(IN_SLICE_1);
            enqueueSomeMails(namePatternForSlice(1), emailCount);

            clock.setInstant(IN_SLICE_2);
            enqueueSomeMails(namePatternForSlice(2), emailCount);

            clock.setInstant(IN_SLICE_3);
            enqueueSomeMails(namePatternForSlice(3), emailCount);

            clock.setInstant(IN_SLICE_5);
            enqueueSomeMails(namePatternForSlice(5), emailCount);

            clock.setInstant(IN_SLICE_7);
            Stream<String> names = Iterators.toStream(mailQueue.browse())
                .map(ManageableMailQueue.MailQueueItemView::getMail)
                .map(Mail::getName);

            assertThat(names).containsExactly(
                "1-1", "1-2", "1-3", "1-4", "1-5",
                "2-1", "2-2", "2-3", "2-4", "2-5",
                "3-1", "3-2", "3-3", "3-4", "3-5",
                "5-1", "5-2", "5-3", "5-4", "5-5");
        }

        @Test
        void browseAndDequeueShouldCombineWellWhenDifferentSlices() throws Exception {
            ManageableMailQueue mailQueue = getManageableMailQueue();
            int emailCount = 5;

            clock.setInstant(IN_SLICE_1);
            enqueueSomeMails(namePatternForSlice(1), emailCount);

            clock.setInstant(IN_SLICE_2);
            enqueueSomeMails(namePatternForSlice(2), emailCount);

            clock.setInstant(IN_SLICE_3);
            enqueueSomeMails(namePatternForSlice(3), emailCount);

            clock.setInstant(IN_SLICE_5);
            enqueueSomeMails(namePatternForSlice(5), emailCount);

            clock.setInstant(IN_SLICE_7);
            dequeueMails(5);
            dequeueMails(5);
            dequeueMails(3);

            Stream<String> names = Iterators.toStream(mailQueue.browse())
                .map(ManageableMailQueue.MailQueueItemView::getMail)
                .map(Mail::getName);

            assertThat(names)
                .containsExactly("3-4", "3-5", "5-1", "5-2", "5-3", "5-4", "5-5");
        }

        private Function<Integer, String> namePatternForSlice(int sliceId) {
            return i -> sliceId + "-" + i;
        }

        @Test
        void mailQueueShouldBeInitializedWhenCreating(CassandraCluster cassandra) {
            org.apache.james.queue.api.MailQueueName name = org.apache.james.queue.api.MailQueueName.of("myQueue");
            mailQueueFactory.createQueue(name);

            boolean initialized = CassandraMailQueueViewTestFactory.isInitialized(cassandra.getConf(), MailQueueName.fromString(name.asString()));
            assertThat(initialized).isTrue();
        }

        @Test
        void enQueueShouldNotThrowOnMailNameWithNegativeHash() {
            String negativehashedString = "this sting will have a negative hash"; //hash value: -1256871313

            assertThatCode(() -> getMailQueue().enQueue(defaultMail().name(negativehashedString).build()))
                .doesNotThrowAnyException();
        }

        @Disabled("JAMES-2614 RabbitMQMailQueueTest::concurrentEnqueueDequeueShouldNotFail is unstable." +
            "The related test is disabled, and need to be re-enabled after investigation and a fix.")
        @Test
        @Override
        public void concurrentEnqueueDequeueShouldNotFail() {

        }

        @Test
        void dequeueShouldWorkAfterNetworkOutage() throws Exception {
            String name1 = "myMail1";
            String name2 = "myMail2";
            String name3 = "myMail3";
            Flux<MailQueue.MailQueueItem> dequeueFlux = Flux.from(getMailQueue().deQueue());
            getMailQueue().enQueue(defaultMail()
                .name(name1)
                .build());

            rabbitMQExtension.getRabbitMQ().pause();
            Thread.sleep(2000);

            getMailQueue().enQueue(defaultMail()
                .name(name2)
                .build());

            rabbitMQExtension.getRabbitMQ().unpause();
            Thread.sleep(100);

            getMailQueue().enQueue(defaultMail()
                .name(name3)
                .build());

            List<MailQueue.MailQueueItem> items = dequeueFlux.take(3).collectList().block(Duration.ofSeconds(10));

            assertThat(items)
                .extracting(item -> item.getMail().getName())
                .containsExactly(name1, name2, name3);
        }

        @Test
        void messagesShouldSurviveRabbitMQRestart() throws Exception {
            String name1 = "myMail1";
            String name2 = "myMail2";
            String name3 = "myMail3";
            Flux<MailQueue.MailQueueItem> dequeueFlux = Flux.from(getMailQueue().deQueue());

            getMailQueue().enQueue(defaultMail()
                .name(name1)
                .build());

            getMailQueue().enQueue(defaultMail()
                .name(name2)
                .build());

            getMailQueue().enQueue(defaultMail()
                .name(name3)
                .build());

            rabbitMQExtension.getRabbitMQ().restart();

            List<MailQueue.MailQueueItem> items = dequeueFlux.take(3).collectList().block(Duration.ofSeconds(10));

            assertThat(items)
                .extracting(item -> item.getMail().getName())
                .containsExactly(name1, name2, name3);
        }

        private void enqueueSomeMails(Function<Integer, String> namePattern, int emailCount) {
            IntStream.rangeClosed(1, emailCount)
                .forEach(Throwing.intConsumer(i -> enQueue(defaultMail()
                    .name(namePattern.apply(i))
                    .build())));
        }

        private void dequeueMails(int times) {
            Flux.from(getManageableMailQueue()
                .deQueue())
                .take(times)
                .flatMap(mailQueueItem -> Mono.fromCallable(() -> {
                    mailQueueItem.done(true);
                    return mailQueueItem;
                }))
                .blockLast();
        }

        @Test
        void dequeueShouldRetryLoadingErrors(CassandraCluster cassandra) throws Exception {
            String name1 = "myMail1";
            String name2 = "myMail2";
            String name3 = "myMail3";

            getMailQueue().enQueue(defaultMail()
                .name(name1)
                .build());

            getMailQueue().enQueue(defaultMail()
                .name(name2)
                .build());

            getMailQueue().enQueue(defaultMail()
                .name(name3)
                .build());

            cassandra.getConf().registerScenario(fail()
                .times(1)
                .whenQueryStartsWith("SELECT * FROM blobs WHERE id=:id;"));

            List<MailQueue.MailQueueItem> items =  Flux.from(getMailQueue().deQueue())
                .onErrorResume(e -> {e.printStackTrace(); return Mono.empty(); })
                .take(3)
                .collectList()
                .block(Duration.ofSeconds(10));

            assertThat(items)
                .extracting(item -> item.getMail().getName())
                .containsExactly(name1, name2, name3);
        }
    }

    @Nested
    class MailQueueSizeMetricsDisabled {
        @RegisterExtension
        MailQueueMetricExtension mailQueueMetricExtension = new MailQueueMetricExtension();

        @BeforeEach
        void setup(CassandraCluster cassandra, MailQueueMetricExtension.MailQueueMetricTestSystem metricTestSystem) throws Exception {
            setUp(cassandra,
                metricTestSystem,
                RabbitMQMailQueueConfiguration.builder()
                    .sizeMetricsEnabled(false)
                    .build());
        }

        @Test
        void constructorShouldNotRegisterGetQueueSizeGaugeWhenSizeMetricsDisabled(MailQueueMetricExtension.MailQueueMetricTestSystem metricTestSystem) {
            ArgumentCaptor<Gauge<?>> gaugeCaptor = ArgumentCaptor.forClass(Gauge.class);
            verify(metricTestSystem.getSpyGaugeRegistry(), never()).register(any(), gaugeCaptor.capture());
        }
    }

    private void setUp(CassandraCluster cassandra,
                       MailQueueMetricExtension.MailQueueMetricTestSystem metricTestSystem,
                       RabbitMQMailQueueConfiguration configuration) throws Exception {
        CassandraBlobStore blobStore = CassandraBlobStore.forTesting(cassandra.getConf());
        MimeMessageStore.Factory mimeMessageStoreFactory = MimeMessageStore.factory(blobStore);
        clock = new UpdatableTickingClock(IN_SLICE_1);

        MailQueueView.Factory mailQueueViewFactory = CassandraMailQueueViewTestFactory.factory(
                clock,
                cassandra.getConf(),
                cassandraCluster.getCassandraConsistenciesConfiguration(),
                CassandraMailQueueViewConfiguration.builder()
                    .bucketCount(THREE_BUCKET_COUNT)
                    .updateBrowseStartPace(UPDATE_BROWSE_START_PACE)
                    .sliceWindow(ONE_HOUR_SLICE_WINDOW)
                    .build(),
            mimeMessageStoreFactory);

        RabbitMQMailQueueFactory.PrivateFactory factory = new RabbitMQMailQueueFactory.PrivateFactory(
            metricTestSystem.getMetricFactory(),
            metricTestSystem.getSpyGaugeRegistry(),
            rabbitMQExtension.getSender(), rabbitMQExtension.getReceiverProvider(),
            mimeMessageStoreFactory,
            BLOB_ID_FACTORY,
            mailQueueViewFactory,
            clock,
            new RawMailQueueItemDecoratorFactory(),
            configuration);
        mqManagementApi = new RabbitMQMailQueueManagement(rabbitMQExtension.managementAPI());
        mailQueueFactory = new RabbitMQMailQueueFactory(rabbitMQExtension.getSender(), mqManagementApi, factory);
        mailQueue = mailQueueFactory.createQueue(SPOOL);
    }
}
