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

package org.apache.james.events;

import javax.inject.Inject;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQManagementAPI;
import org.apache.james.core.healthcheck.ComponentName;
import org.apache.james.core.healthcheck.HealthCheck;
import org.apache.james.core.healthcheck.Result;

import reactor.core.publisher.Mono;

public class RabbitMQEventBusDeadLetterQueueHealthCheck implements HealthCheck {
    public static final ComponentName COMPONENT_NAME = new ComponentName("RabbitMQEventBusDeadLetterQueueHealthCheck");
    private static final String DEFAULT_VHOST = "/";

    private final RabbitMQConfiguration configuration;
    private final NamingStrategy mailboxEventNamingStrategy;
    private final RabbitMQManagementAPI api;

    @Inject
    public RabbitMQEventBusDeadLetterQueueHealthCheck(RabbitMQConfiguration configuration, NamingStrategy mailboxEventNamingStrategy) {
        this.configuration = configuration;
        this.mailboxEventNamingStrategy = mailboxEventNamingStrategy;
        this.api = RabbitMQManagementAPI.from(configuration);
    }

    @Override
    public ComponentName componentName() {
        return COMPONENT_NAME;
    }

    @Override
    public Mono<Result> check() {
        return Mono.fromCallable(() -> api.queueDetails(configuration.getVhost().orElse(DEFAULT_VHOST), mailboxEventNamingStrategy.deadLetterQueue().getName()).getQueueLength())
            .mergeWith(Mono.fromCallable(() -> api.queueDetails(configuration.getVhost().orElse(DEFAULT_VHOST), NamingStrategy.JMAP_NAMING_STRATEGY.deadLetterQueue().getName()).getQueueLength()))
            .reduce(Long::sum)
            .map(queueSize -> {
                if (queueSize != 0) {
                    return Result.degraded(COMPONENT_NAME, "RabbitMQ dead letter queue of the event bus contain events. This might indicate transient failure on event processing.");
                }
                return Result.healthy(COMPONENT_NAME);
            })
            .onErrorResume(e -> Mono.just(Result.unhealthy(COMPONENT_NAME, "Error checking RabbitMQEventBusDeadLetterHealthCheck", e)));
    }
}
