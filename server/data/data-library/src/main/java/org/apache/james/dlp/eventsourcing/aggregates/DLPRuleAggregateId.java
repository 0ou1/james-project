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

package org.apache.james.dlp.eventsourcing.aggregates;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.james.core.Domain;
import org.apache.james.eventsourcing.AggregateId;

public class DLPRuleAggregateId implements AggregateId {
    private static final String SEPARATOR = "/";
    private static final String PREFIX = "QuotaThreasholdEvents";

    private final Domain domain;

    public DLPRuleAggregateId(Domain domain) {
        this.domain = domain;
    }

    @Override
    public String asAggregateKey() {
        return Stream.of(
                PREFIX,
                domain.asString())
            .collect(Collectors.joining(SEPARATOR));
    }

    @Override
    public final boolean equals(Object o) {
        if (o instanceof DLPRuleAggregateId) {
            DLPRuleAggregateId that = (DLPRuleAggregateId) o;

            return Objects.equals(this.domain, that.domain);
        }
        return false;
    }

    @Override
    public final int hashCode() {
        return Objects.hash(domain);
    }
}
