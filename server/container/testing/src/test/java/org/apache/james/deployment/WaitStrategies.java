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

package org.apache.james.deployment;

import static org.apache.james.deployment.Constants.WEBADMIN_PORT;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.rnorth.ducttape.unreliables.Unreliables;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.primitives.Ints;

public class WaitStrategies {
    private static final String CLI = "/root/james-server-app-3.2.0-SNAPSHOT/bin/james-cli.sh";

    public static final WaitStrategy webAdminWaitStrategy = new HttpWaitStrategy()
            .forPort(WEBADMIN_PORT)
            .forPath("/status")
            .forResponsePredicate(WaitStrategies::isStarted);

    private static boolean isStarted(String string) {
        try {
            return new ObjectMapper().readValue(string, JsonNode.class)
                .get("started")
                .asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    public static WaitStrategy cliWaitStrategy(GenericContainer<?> james) {
        return new CliWaitStrategy(james);
    }

    public static class CliWaitStrategy implements WaitStrategy {
        private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);
        private final GenericContainer<?> james;
        private Duration timeout = DEFAULT_TIMEOUT;

        public CliWaitStrategy(GenericContainer<?> james) {
            this(james, DEFAULT_TIMEOUT);
        }

        public CliWaitStrategy(GenericContainer<?> james, Duration timeout) {
            this.james = james;
            this.timeout = timeout;
        }

        @Override
        public void waitUntilReady(WaitStrategyTarget waitStrategyTarget) {
            Unreliables.retryUntilTrue(Ints.checkedCast(timeout.getSeconds()), TimeUnit.SECONDS, () -> {
                    try {
                        String stdout = james.execInContainer(CLI, "-h", "127.0.0.1", "-p", "9999", "ListDomains").getStdout();
                        System.out.println(stdout);
                        return stdout
                            .contains("command executed sucessfully");
                    } catch (IOException | InterruptedException e) {
                        return false;
                    }
                }
            );
        }

        @Override
        public WaitStrategy withStartupTimeout(Duration startupTimeout) {
            return new CliWaitStrategy(james, startupTimeout);
        }
    }

    public static class CassandraWaitStrategy implements WaitStrategy {
        private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);
        private final GenericContainer<?> cassandraContainer;
        private final Duration timeout;

        public CassandraWaitStrategy(GenericContainer<?> cassandraContainer) {
            this(cassandraContainer, DEFAULT_TIMEOUT);
        }

        public CassandraWaitStrategy(GenericContainer<?> cassandraContainer, Duration timeout) {
            this.cassandraContainer = cassandraContainer;
            this.timeout = timeout;
        }

        @Override
        public void waitUntilReady(WaitStrategyTarget waitStrategyTarget) {
            Unreliables.retryUntilTrue(Ints.checkedCast(timeout.getSeconds()), TimeUnit.SECONDS, () -> {
                    try {
                        return cassandraContainer
                            .execInContainer("cqlsh", "-e", "show host")
                            .getStdout()
                            .contains("Connected to Test Cluster");
                    } catch (IOException | InterruptedException e) {
                        return false;
                    }
                }
            );
        }

        @Override
        public WaitStrategy withStartupTimeout(Duration startupTimeout) {
            return new CassandraWaitStrategy(cassandraContainer, startupTimeout);
        }
    }

}
