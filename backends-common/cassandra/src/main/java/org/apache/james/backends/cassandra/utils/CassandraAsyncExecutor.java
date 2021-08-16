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

package org.apache.james.backends.cassandra.utils;

import java.util.Optional;

import javax.inject.Inject;

import com.datastax.dse.driver.api.core.cql.reactive.ReactiveResultSet;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.Statement;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CassandraAsyncExecutor {

    private final CqlSession session;

    @Inject
    public CassandraAsyncExecutor(CqlSession session) {
        this.session = session;
    }

    public ReactiveResultSet execute(Statement statement) {
        return session.executeReactive(statement);
    }

    public Mono<Boolean> executeReturnApplied(Statement statement) {
        return Mono.from(execute(statement)
            .wasApplied());
    }

    public Mono<Void> executeVoid(Statement statement) {
        return Mono.from(execute(statement))
                .then();
    }

    public Mono<Row> executeSingleRow(Statement statement) {
        return Mono.from(execute(statement));
    }

    public Flux<Row> executeRows(Statement statement) {
        return Flux.from(execute(statement));
    }

    public Mono<Optional<Row>> executeSingleRowOptional(Statement statement) {
        return executeSingleRow(statement)
            .map(Optional::ofNullable)
            .switchIfEmpty(Mono.just(Optional.empty()));
    }

    public Mono<Boolean> executeReturnExists(Statement statement) {
        return executeSingleRow(statement)
                .hasElement();
    }
}
