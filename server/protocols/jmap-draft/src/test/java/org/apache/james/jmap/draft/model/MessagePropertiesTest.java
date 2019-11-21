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

package org.apache.james.jmap.draft.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.apache.james.jmap.draft.model.MessageProperties.HeaderProperty;
import org.apache.james.jmap.draft.model.MessageProperties.MessageProperty;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

class MessagePropertiesTest {

    @Test
    void toOutputPropertiesShouldReturnAllMessagePropertiesWhenAbsent() {
        MessageProperties actual = new MessageProperties(Optional.empty()).toOutputProperties();
        assertThat(actual.getOptionalMessageProperties()).hasValue(MessageProperty.allOutputProperties());
    }
    
    @Test
    void toOutputPropertiesShouldReturnEmptyHeaderPropertiesWhenAbsent() {
        MessageProperties actual = new MessageProperties(Optional.empty()).toOutputProperties();
        assertThat(actual.getOptionalHeadersProperties()).isEmpty();
    }

    @Test
    void toOutputPropertiesShouldReturnTextBodyWhenBodyRequested() {
        MessageProperties actual = new MessageProperties(Optional.of(ImmutableSet.of("body"))).toOutputProperties();
        assertThat(actual.getOptionalMessageProperties())
            .hasValueSatisfying(value -> 
                assertThat(value).contains(MessageProperty.textBody).doesNotContain(MessageProperty.body));
    }

    @Test
    void toOutputPropertiesShouldReturnIsUnread() {
        MessageProperties actual = new MessageProperties(Optional.of(ImmutableSet.of("isUnread"))).toOutputProperties();
        assertThat(actual.getOptionalMessageProperties())
                .hasValueSatisfying(value ->
                        assertThat(value).contains(MessageProperty.isUnread));
    }

    @Test
    void toOutputPropertiesShouldReturnMandatoryPropertiesWhenEmptyRequest() {
        MessageProperties actual = new MessageProperties(Optional.of(ImmutableSet.of())).toOutputProperties();
        assertThat(actual.getOptionalMessageProperties())
            .hasValue(ImmutableSet.of(MessageProperty.id));
    }

    @Test
    void toOutputPropertiesShouldReturnAllHeadersWhenHeadersAndIndividualHeadersRequested() {
        MessageProperties actual = new MessageProperties(
            Optional.of(ImmutableSet.of("headers.X-Spam-Score", "headers"))).toOutputProperties();
        assertThat(actual.getOptionalMessageProperties()).hasValueSatisfying(
            value -> assertThat(value).contains(MessageProperty.headers)
        );
        assertThat(actual.getOptionalHeadersProperties()).isEmpty();
    }

    @Test
    void toOutputPropertiesShouldReturnHeadersMessagePropertyWhenIndividualHeadersRequested() {
        MessageProperties actual = new MessageProperties(
            Optional.of(ImmutableSet.of("headers.X-Spam-Score"))).toOutputProperties();
        assertThat(actual.getOptionalMessageProperties()).hasValueSatisfying(
            value -> assertThat(value).contains(MessageProperty.headers)
        );
        assertThat(actual.getOptionalHeadersProperties()).hasValueSatisfying(
            value -> assertThat(value).contains(HeaderProperty.fromFieldName("x-spam-score"))
        );
    }
}
