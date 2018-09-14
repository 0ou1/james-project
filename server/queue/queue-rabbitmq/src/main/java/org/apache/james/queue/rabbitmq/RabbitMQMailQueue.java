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

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.inject.Inject;
import javax.mail.internet.MimeMessage;

import org.apache.james.blob.api.BlobId;
import org.apache.james.blob.api.Store;
import org.apache.james.blob.mail.MimeMessagePartsId;
import org.apache.james.metrics.api.GaugeRegistry;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.queue.api.MailQueue;
import org.apache.mailet.Mail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.fge.lambdas.Throwing;
import com.google.common.annotations.VisibleForTesting;

public class RabbitMQMailQueue implements MailQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMQMailQueue.class);

    static class Factory {
        private final MetricFactory metricFactory;
        private final GaugeRegistry gaugeRegistry;
        private final RabbitClient rabbitClient;
        private final Store<MimeMessage, MimeMessagePartsId> mimeMessageStore;
        private final MailReferenceSerializer mailReferenceSerializer;
        private final Function<MailReferenceDTO, Mail> mailLoader;

        @Inject
        @VisibleForTesting Factory(MetricFactory metricFactory, GaugeRegistry gaugeRegistry, RabbitClient rabbitClient,
                                   Store<MimeMessage, MimeMessagePartsId> mimeMessageStore, BlobId.Factory blobIdFactory) {
            this.metricFactory = metricFactory;
            this.gaugeRegistry = gaugeRegistry;
            this.rabbitClient = rabbitClient;
            this.mimeMessageStore = mimeMessageStore;
            mailReferenceSerializer = new MailReferenceSerializer(
                new ObjectMapper()
                    .registerModule(new Jdk8Module())
                    .registerModule(new JavaTimeModule())
                    .registerModule(new GuavaModule()));
            mailLoader = Throwing.function(new MailLoader(mimeMessageStore, blobIdFactory)::load).sneakyThrow();
        }

        RabbitMQMailQueue create(MailQueueName mailQueueName) {
            return new RabbitMQMailQueue(metricFactory, gaugeRegistry, mailQueueName,
                new Enqueuer(mailQueueName, rabbitClient, mimeMessageStore, mailReferenceSerializer, metricFactory),
                new Dequeuer(mailQueueName, rabbitClient, mailLoader, mailReferenceSerializer, metricFactory));
        }
    }

    private final MailQueueName name;
    private final MetricFactory metricFactory;
    private final GaugeRegistry gaugeRegistry;
    private final Enqueuer enqueuer;
    private final Dequeuer dequeuer;

    RabbitMQMailQueue(MetricFactory metricFactory, GaugeRegistry gaugeRegistry, MailQueueName name,
                      Enqueuer enqueuer, Dequeuer dequeuer) {

        this.name = name;
        this.enqueuer = enqueuer;
        this.dequeuer = dequeuer;

        this.metricFactory = metricFactory;
        this.gaugeRegistry = gaugeRegistry;
    }

    @Override
    public String getName() {
        return name.asString();
    }

    @Override
    public void enQueue(Mail mail, long delay, TimeUnit unit) throws MailQueueException {
        if (delay > 0) {
            LOGGER.info("Ignored delay upon enqueue of {} : {} {}.", mail.getName(), delay, unit);
        }
        enQueue(mail);
    }

    @Override
    public void enQueue(Mail mail) throws MailQueueException {
        metricFactory.runPublishingTimerMetric(ENQUEUED_TIMER_METRIC_NAME_PREFIX + name.asString(),
            Throwing.runnable(() -> enqueuer.enQueue(mail)).sneakyThrow());
    }

    @Override
    public MailQueueItem deQueue() throws MailQueueException {
        return metricFactory.runPublishingTimerMetric(DEQUEUED_TIMER_METRIC_NAME_PREFIX + name.asString(),
            Throwing.supplier(dequeuer::deQueue).sneakyThrow());
    }
}