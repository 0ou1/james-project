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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

public class DockerRabbitMQ {
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerRabbitMQ.class);

    private static final int DEFAULT_RABBITMQ_PORT = 5672;
    private GenericContainer<?> container;

    @SuppressWarnings("resource")
    public DockerRabbitMQ() {
        container = new GenericContainer<>("rabbitmq:3.7.3")
                .withCreateContainerCmdModifier(cmd -> cmd.withHostName("my-rabbit"))
                .withExposedPorts(DEFAULT_RABBITMQ_PORT)
            .withLogConsumer(outputFrame -> LOGGER.debug(outputFrame.getUtf8String()))
                .waitingFor(new RabbitMQWaitStrategy());
    }

    public String getHostIp() {
        return container.getContainerIpAddress();
    }

    public Integer getPort() {
        return container.getMappedPort(DEFAULT_RABBITMQ_PORT);
    }

    public String getUsername() {
        return "guest";
    }

    public String getPassword() {
        return "guest";
    }

    public void start() {
        container.start();
    }

    public void stop() {
        container.stop();
    }

    public void restart() {
        DockerClientFactory.instance().client()
            .restartContainerCmd(container.getContainerId());
    }
}
