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

import static org.apache.james.queue.api.MailQueue.ENQUEUED_METRIC_NAME_PREFIX;

import java.util.concurrent.CompletableFuture;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import org.apache.james.blob.api.Store;
import org.apache.james.blob.mail.MimeMessagePartsId;
import org.apache.james.metrics.api.Metric;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.queue.api.MailQueue;
import org.apache.mailet.Mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

class Enqueuer {
    private final MailQueueName name;
    private final RabbitClient rabbitClient;
    private final Store<MimeMessage, MimeMessagePartsId> mimeMessageStore;
    private final ObjectMapper objectMapper;
    private final Metric enqueueMetric;

    Enqueuer(MailQueueName name, RabbitClient rabbitClient, Store<MimeMessage, MimeMessagePartsId> mimeMessageStore,
             ObjectMapper objectMapper, MetricFactory metricFactory) {
        this.name = name;
        this.rabbitClient = rabbitClient;
        this.mimeMessageStore = mimeMessageStore;
        this.objectMapper = objectMapper;
        this.enqueueMetric = metricFactory.generate(ENQUEUED_METRIC_NAME_PREFIX + name.asString());
    }

    void enQueue(Mail mail) throws MailQueue.MailQueueException {
        MimeMessagePartsId partsId = saveBlobs(mail).join();
        MailReferenceDTO mailDTO = MailReferenceDTO.fromMail(mail, partsId);
        byte[] message = getMessageBytes(mailDTO);
        rabbitClient.publish(name, message);

        enqueueMetric.increment();
    }

    private CompletableFuture<MimeMessagePartsId> saveBlobs(Mail mail) throws MailQueue.MailQueueException {
        try {
            return mimeMessageStore.save(mail.getMessage());
        } catch (MessagingException e) {
            throw new MailQueue.MailQueueException("Error while saving blob", e);
        }
    }

    private byte[] getMessageBytes(MailReferenceDTO mailDTO) throws MailQueue.MailQueueException {
        try {
            return objectMapper.writeValueAsBytes(mailDTO);
        } catch (JsonProcessingException e) {
            throw new MailQueue.MailQueueException("Unable to serialize message", e);
        }
    }
}
