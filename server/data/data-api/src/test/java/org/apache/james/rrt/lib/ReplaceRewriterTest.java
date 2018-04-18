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

package org.apache.james.rrt.lib;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.core.User;
import org.junit.jupiter.api.Test;

public class ReplaceRewriterTest {
    @Test
    public void rewriteShouldSubstituteAddress() throws Exception {
        String newAddress = "newaddress@newdomain";
        assertThat(
            new UserRewritter.ReplaceRewriter()
                .generateUserRewriter(newAddress)
                .rewrite(User.fromUsername("old@passed")))
            .contains(User.fromUsername(newAddress));
    }
    @Test
    public void rewriteShouldSubstituteAddressWhenNoLocalPart() throws Exception {
        String newAddress = "newaddress@newdomain";
        assertThat(
            new UserRewritter.ReplaceRewriter()
                .generateUserRewriter(newAddress)
                .rewrite(User.fromUsername("old")))
            .contains(User.fromUsername(newAddress));
    }

    @Test
    public void rewriteShouldSubstituteAddressWhenNoLocalPartInRewrittenAddress() throws Exception {
        String newAddress = "newaddress";
        assertThat(
            new UserRewritter.ReplaceRewriter()
                .generateUserRewriter(newAddress)
                .rewrite(User.fromUsername("old@passed")))
            .contains(User.fromUsername(newAddress));
    }
}
