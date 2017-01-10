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

package org.apache.james.transport.mailets.remoteDelivery;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.apache.james.dnsservice.api.DNSService;
import org.apache.james.lifecycle.api.LifecycleUtil;
import org.apache.james.metrics.api.Metric;
import org.apache.james.queue.api.MailPrioritySupport;
import org.apache.james.queue.api.MailQueue;
import org.apache.mailet.Mail;
import org.apache.mailet.MailetContext;
import org.slf4j.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;

public class DeliveryRunnable implements Runnable {

    public static final Supplier<Date> CURRENT_DATE_SUPPLIER = new Supplier<Date>() {
        @Override
        public Date get() {
            return new Date();
        }
    };
    public static final AtomicBoolean DEFAULT_NOT_STARTED = new AtomicBoolean(false);

    private final MailQueue queue;
    private final RemoteDeliveryConfiguration configuration;
    private final Metric outgoingMailsMetric;
    private final Logger logger;
    private final Bouncer bouncer;
    private final MailDelivrer mailDelivrer;
    private final VolatileIsDestroyed volatileIsDestroyed;
    private final Supplier<Date> dateSupplier;

    public DeliveryRunnable(MailQueue queue, RemoteDeliveryConfiguration configuration, DNSService dnsServer, Metric outgoingMailsMetric,
                            Logger logger, MailetContext mailetContext, Bouncer bouncer, VolatileIsDestroyed volatileIsDestroyed) {
        this(queue, configuration, outgoingMailsMetric, logger, bouncer,
            new MailDelivrer(configuration, new MailDelivrerToHost(configuration, mailetContext, logger), dnsServer, bouncer, logger),
            volatileIsDestroyed, CURRENT_DATE_SUPPLIER);
    }

    @VisibleForTesting
    DeliveryRunnable(MailQueue queue, RemoteDeliveryConfiguration configuration, Metric outgoingMailsMetric, Logger logger, Bouncer bouncer,
                     MailDelivrer mailDelivrer, VolatileIsDestroyed volatileIsDestroyed, Supplier<Date> dateSupplier) {
        this.queue = queue;
        this.configuration = configuration;
        this.outgoingMailsMetric = outgoingMailsMetric;
        this.logger = logger;
        this.bouncer = bouncer;
        this.mailDelivrer = mailDelivrer;
        this.volatileIsDestroyed = volatileIsDestroyed;
        this.dateSupplier = dateSupplier;
    }

    @Override
    public void run() {
        try {
            while (!Thread.interrupted() && !volatileIsDestroyed.isDestroyed()) {
                runStep();
            }
        } finally {
            // Restore the thread state to non-interrupted.
            Thread.interrupted();
        }
    }

    private void runStep() {
        try {
            // Get the 'mail' object that is ready for deliverying. If no message is
            // ready, the 'accept' will block until message is ready.
            // The amount of time to block is determined by the 'getWaitTime' method of the MultipleDelayFilter.
            MailQueue.MailQueueItem queueItem = queue.deQueue();
            Mail mail = queueItem.getMail();

            try {
                if (configuration.isDebug()) {
                    logger.debug(Thread.currentThread().getName() + " will process mail " + mail.getName());
                }
                attemptDelivery(mail);
                LifecycleUtil.dispose(mail);
                mail = null;
                queueItem.done(true);
            } catch (Exception e) {
                // Prevent unexpected exceptions from causing looping by removing message from outgoing.
                // DO NOT CHANGE THIS to catch Error!
                // For example, if there were an OutOfMemory condition caused because
                // something else in the server was abusing memory, we would not want to start purging the retrying spool!
                logger.error("Exception caught in RemoteDelivery.run()", e);
                LifecycleUtil.dispose(mail);
                queueItem.done(false);
            }

        } catch (Throwable e) {
            if (!volatileIsDestroyed.isDestroyed()) {
                logger.error("Exception caught in RemoteDelivery.run()", e);
            }
        }
    }

    @VisibleForTesting
    void attemptDelivery(Mail mail) throws MailQueue.MailQueueException {
        ExecutionResult executionResult = mailDelivrer.deliver(mail);
        switch (executionResult.getExecutionState()) {
            case SUCCESS:
                outgoingMailsMetric.increment();
                break;
            case TEMPORARY_FAILURE:
                handleTemporaryFailure(mail, executionResult);
                break;
            case PERMANENT_FAILURE:
                bouncer.bounce(mail, executionResult.getException().orNull());
                break;
        }
    }

    private void handleTemporaryFailure(Mail mail, ExecutionResult executionResult) throws MailQueue.MailQueueException {
        if (!mail.getState().equals(Mail.ERROR)) {
            mail.setState(Mail.ERROR);
            DeliveryRetriesHelper.initRetries(mail);
            mail.setLastUpdated(dateSupplier.get());
        }
        int retries = DeliveryRetriesHelper.retrieveRetries(mail);

        if (retries < configuration.getMaxRetries()) {
            reAttemptDelivery(mail, retries);
        } else {
            logger.debug("Bouncing message " + mail.getName() + " after " + retries + " retries");
            bouncer.bounce(mail, new Exception("Too many retries failure. Bouncing after " + retries + " retries.", executionResult.getException().orNull()));
        }
    }

    private void reAttemptDelivery(Mail mail, int retries) throws MailQueue.MailQueueException {
        logger.debug("Storing message " + mail.getName() + " into outgoing after " + retries + " retries");
        DeliveryRetriesHelper.incrementRetries(mail);
        mail.setLastUpdated(dateSupplier.get());
        // Something happened that will delay delivery. Store it back in the retry repository.
        long delay = getNextDelay(DeliveryRetriesHelper.retrieveRetries(mail));

        if (configuration.isUsePriority()) {
            // Use lowest priority for retries. See JAMES-1311
            mail.setAttribute(MailPrioritySupport.MAIL_PRIORITY, MailPrioritySupport.LOW_PRIORITY);
        }
        queue.enQueue(mail, delay, TimeUnit.MILLISECONDS);
    }

    private long getNextDelay(int retry_count) {
        if (retry_count > configuration.getDelayTimes().size()) {
            return Delay.DEFAULT_DELAY_TIME;
        }
        return configuration.getDelayTimes().get(retry_count - 1);
    }
}
