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

package org.apache.james.jmap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Java6Assertions.assertThat;

import java.util.Optional;

import org.apache.james.util.Port;
import org.junit.jupiter.api.Test;

class JMAPConfigurationTest {

    public static final boolean ENABLED = true;
    public static final boolean DISABLED = false;

    @Test
    void buildShouldThrowWhenEnableIsMissing() {
        assertThatThrownBy(() -> JMAPConfiguration.builder().build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("You should specify if JMAP server should be started");
    }

    @Test
    void buildShouldWorkWhenRandomPort() {
        JMAPConfiguration expectedJMAPConfiguration = new JMAPConfiguration(ENABLED, false, Optional.empty());

        JMAPConfiguration jmapConfiguration = JMAPConfiguration.builder()
            .enable()
            .randomPort()
            .build();
        assertThat(jmapConfiguration).isEqualToComparingFieldByField(expectedJMAPConfiguration);
    }

    @Test
    public void buildShouldWorkWhenFixedPort() {
        JMAPConfiguration expectedJMAPConfiguration = new JMAPConfiguration(ENABLED, false, Optional.of(Port.of(80)));

        JMAPConfiguration jmapConfiguration = JMAPConfiguration.builder()
            .enable()
            .port(Port.of(80))
            .build();

        assertThat(jmapConfiguration).isEqualToComparingFieldByField(expectedJMAPConfiguration);
    }

    @Test
    public void buildShouldWorkWhenWiretap() {
        JMAPConfiguration expectedJMAPConfiguration = new JMAPConfiguration(ENABLED, true, Optional.empty());

        JMAPConfiguration jmapConfiguration = JMAPConfiguration.builder()
            .enable()
            .wiretap()
            .randomPort()
            .build();

        assertThat(jmapConfiguration).isEqualToComparingFieldByField(expectedJMAPConfiguration);
    }

    @Test
    public void buildShouldWorkWhenDisabled() {
        JMAPConfiguration expectedJMAPConfiguration = new JMAPConfiguration(DISABLED, false, Optional.empty());

        JMAPConfiguration jmapConfiguration = JMAPConfiguration.builder()
            .disable()
            .build();
        assertThat(jmapConfiguration).isEqualToComparingFieldByField(expectedJMAPConfiguration);
    }
}
