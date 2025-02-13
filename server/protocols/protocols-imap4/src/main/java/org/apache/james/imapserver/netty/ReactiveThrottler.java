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

package org.apache.james.imapserver.netty;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.james.metrics.api.GaugeRegistry;
import org.reactivestreams.Publisher;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public class ReactiveThrottler {
    public static class RejectedException extends RuntimeException {
        public RejectedException(String message) {
            super(message);
        }
    }

    private final int maxConcurrentRequests;
    private final int maxQueueSize;
    // In flight + executing
    private final AtomicInteger concurrentRequests = new AtomicInteger(0);
    private final Queue<Publisher<Void>> queue = new ConcurrentLinkedQueue<>();

    public ReactiveThrottler(GaugeRegistry gaugeRegistry, int maxConcurrentRequests, int maxQueueSize) {
        gaugeRegistry.register("imap.request.queue.size", () -> Math.max(concurrentRequests.get() - maxConcurrentRequests, 0));

        this.maxConcurrentRequests = maxConcurrentRequests;
        this.maxQueueSize = maxQueueSize;
    }

    public Mono<Void> throttle(Publisher<Void> task) {
        if (maxConcurrentRequests < 0) {
            return Mono.from(task);
        }
        int requestNumber = concurrentRequests.incrementAndGet();

        if (requestNumber <= maxConcurrentRequests) {
            // We have capacity for one more concurrent request
            return Mono.from(task)
                .doFinally(any -> onRequestDone());
        } else if (requestNumber <= maxQueueSize + maxConcurrentRequests) {
            // Queue the request for later
            Sinks.One<Void> one = Sinks.one();
            queue.add(Mono.from(task)
                .then(Mono.fromRunnable(() -> one.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST))));
            // Let the caller await task completion
            return one.asMono();
        } else {
            concurrentRequests.decrementAndGet();

            return Mono.error(new RejectedException(
                String.format(
                    "The IMAP server has reached its maximum capacity "
                        + "(concurrent requests: %d, queue size: %d)",
                    maxConcurrentRequests, maxQueueSize)));
        }
    }

    private void onRequestDone() {
        concurrentRequests.decrementAndGet();
        Publisher<Void> throttled = queue.poll();
        if (throttled != null) {
            Mono.from(throttled)
                .doFinally(any -> onRequestDone())
                .subscribe();
        }
    }
}
