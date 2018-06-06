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
package org.apache.james.mailbox.spamassassin;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.Host;
import org.apache.james.util.scanner.SpamAssassinInvoker;

import com.github.fge.lambdas.Throwing;
import com.google.common.util.concurrent.MoreExecutors;

public class SpamAssassin {
    private static ExecutorService generateExecutor(SpamAssassinConfiguration configuration) {
        if (configuration.isAsynchronous()) {
            return Executors.newFixedThreadPool(configuration.getThreadCount());
        }
        return MoreExecutors.newDirectExecutorService();
    }

    private final MetricFactory metricFactory;
    private final SpamAssassinConfiguration spamAssassinConfiguration;
    private final ExecutorService executor;

    @Inject
    public SpamAssassin(MetricFactory metricFactory, SpamAssassinConfiguration spamAssassinConfiguration) {
        this.metricFactory = metricFactory;
        this.spamAssassinConfiguration = spamAssassinConfiguration;
        this.executor = generateExecutor(spamAssassinConfiguration);
    }

    public void learnSpam(List<InputStream> messages, String user) {
        executor.submit(() -> doLearnSpam(messages, user));
    }

    public void learnHam(List<InputStream> messages, String user) {
        executor.submit(() -> doLearnHam(messages, user));
    }

    private void doLearnSpam(List<InputStream> messages, String user) {
        if (spamAssassinConfiguration.isEnable()) {
            Host host = spamAssassinConfiguration.getHost().get();
            SpamAssassinInvoker invoker = new SpamAssassinInvoker(metricFactory, host.getHostName(), host.getPort());
            messages
                .forEach(Throwing.consumer(message -> invoker.learnAsSpam(message, user)));
        }
    }

    private void doLearnHam(List<InputStream> messages, String user) {
        if (spamAssassinConfiguration.isEnable()) {
            Host host = spamAssassinConfiguration.getHost().get();
            SpamAssassinInvoker invoker = new SpamAssassinInvoker(metricFactory, host.getHostName(), host.getPort());
            messages
                .forEach(Throwing.consumer(message -> invoker.learnAsHam(message, user)));
        }
    }
}
