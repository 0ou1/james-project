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

package org.apache.james.core.builder;

import static org.assertj.core.api.Assertions.assertThat;

import javax.mail.internet.MimeMessage;

import org.junit.Test;

public class MimeMessageBuilderTest {

    @Test
    public void buildShouldPreserveMessageID() throws Exception {
        String messageID = "<abc@123>";
        MimeMessage build = MimeMessageBuilder.mimeMessageBuilder()
            .addHeader("Message-ID", messageID)
            .build();

        assertThat(build.getMessageID())
            .isEqualTo(messageID);
    }

}