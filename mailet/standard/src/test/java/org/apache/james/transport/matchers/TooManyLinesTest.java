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

package org.apache.james.transport.matchers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;

import javax.mail.MessagingException;

import org.apache.james.core.MailAddress;
import org.apache.james.javax.MimeMessageBuilder;
import org.apache.mailet.base.test.FakeMail;
import org.apache.mailet.base.test.FakeMatcherConfig;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TooManyLinesTest {

    private TooManyLines testee;

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Before
    public void setUp() {
        testee = new TooManyLines();
    }

    @Test
    public void initShouldThrowOnAbsentCondition() throws Exception {
        expectedException.expect(MessagingException.class);

        testee.init(FakeMatcherConfig.builder().matcherName("name").build());
    }

    @Test
    public void initShouldThrowOnInvalidCondition() throws Exception {
        expectedException.expect(MessagingException.class);

        testee.init(
            FakeMatcherConfig.builder()
                .condition("a")
                .matcherName("name")
                .build());
    }

    @Test
    public void initShouldThrowOnEmptyCondition() throws Exception {
        expectedException.expect(MessagingException.class);

        testee.init(FakeMatcherConfig.builder()
            .condition("")
            .matcherName("name")
            .build());
    }

    @Test
    public void initShouldThrowOnZeroCondition() throws Exception {
        expectedException.expect(MessagingException.class);

        testee.init(FakeMatcherConfig.builder()
            .condition("0")
            .matcherName("name")
            .build());
    }

    @Test
    public void initShouldThrowOnNegativeCondition() throws MessagingException {
        expectedException.expect(MessagingException.class);

        testee.init(FakeMatcherConfig.builder()
            .condition("-10")
            .matcherName("name")
            .build());
    }

    @Test
    public void matchShouldReturnNoRecipientWhenMailHaveNoMimeMessageAndConditionIs100() throws Exception {
        testee.init(FakeMatcherConfig.builder()
            .condition("100")
            .matcherName("name")
            .build());

        Collection<MailAddress> result = testee.match(FakeMail.builder().build());

        assertThat(result).isEmpty();

    }

    @Test
    public void matchShouldAcceptMailsUnderLimit() throws Exception {
        testee.init(FakeMatcherConfig.builder()
            .condition("100")
            .matcherName("name")
            .build());

        FakeMail fakeMail = FakeMail.builder()
            .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
                .setMultipartWithBodyParts(MimeMessageBuilder.bodyPartBuilder()
                    .data("content")))
            .build();

        Collection<MailAddress> result = testee.match(fakeMail);

        assertThat(result).isEmpty();
    }

    @Test
    public void matchShouldRejectMailsOverLimit() throws Exception {
        testee.init(FakeMatcherConfig.builder().condition("10").matcherName("name").build());

        FakeMail fakeMail = FakeMail.builder()
            .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
                .setMultipartWithBodyParts(
                    MimeMessageBuilder.bodyPartBuilder()
                        .data("1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11")))
            .build();

        Collection<MailAddress> result = testee.match(fakeMail);

        assertThat(result).containsAll(fakeMail.getRecipients());
    }

}
