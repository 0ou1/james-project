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

package org.apache.james.event.json.dtos;

import static org.apache.james.event.json.SerializerFixture.DTO_JSON_SERIALIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.james.core.Username;

import org.junit.jupiter.api.Test;

import play.api.libs.json.JsError;
import play.api.libs.json.JsNull$;
import play.api.libs.json.JsNumber;
import play.api.libs.json.JsString;
import scala.math.BigDecimal;

class UserTest {
    @Test
    void userShouldBeWellSerialized() {
        assertThat(DTO_JSON_SERIALIZE.userWriters().writes(Username.of("bob")))
            .isEqualTo(new JsString("bob"));
    }

    @Test
    void userShouldBeWellDeSerialized() {
        assertThat(DTO_JSON_SERIALIZE.userReads().reads(new JsString("bob")).get())
            .isEqualTo(Username.of("bob"));
    }

    @Test
    void userShouldBeWellSerializedWhenVirtualHosting() {
        assertThat(DTO_JSON_SERIALIZE.userWriters().writes(Username.of("bob@domain")))
            .isEqualTo(new JsString("bob@domain"));
    }

    @Test
    void userShouldBeWellDeSerializedWhenVirtualHosting() {
        assertThat(DTO_JSON_SERIALIZE.userReads().reads(new JsString("bob@domain")).get())
            .isEqualTo(Username.of("bob@domain"));
    }

    @Test
    void userDeserializationShouldReturnErrorWhenNumber() {
        assertThat(DTO_JSON_SERIALIZE.userReads().reads(new JsNumber(BigDecimal.valueOf(18))))
            .isInstanceOf(JsError.class);
    }

    @Test
    void userDeserializationShouldReturnErrorWhenNull() {
        assertThat(DTO_JSON_SERIALIZE.userReads().reads(JsNull$.MODULE$))
            .isInstanceOf(JsError.class);
    }

    @Test
    void userDeserializationShouldThrowWhenBadUsername() {
        assertThatThrownBy(() -> DTO_JSON_SERIALIZE.userReads().reads(new JsString("bob@bad@bad")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userDeserializationShouldThrowWhenEmpty() {
        assertThatThrownBy(() -> DTO_JSON_SERIALIZE.userReads().reads(new JsString("")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
