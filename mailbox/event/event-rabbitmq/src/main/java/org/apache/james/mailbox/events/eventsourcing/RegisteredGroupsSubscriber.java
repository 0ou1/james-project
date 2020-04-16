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

package org.apache.james.mailbox.events.eventsourcing;

import java.util.List;

import org.apache.james.eventsourcing.Event;
import org.apache.james.eventsourcing.Subscriber;
import org.apache.james.mailbox.events.Group;
import org.reactivestreams.Publisher;

import com.google.common.collect.Sets;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class RegisteredGroupsSubscriber implements Subscriber {
    @FunctionalInterface
    public interface Unregisterer {
        Publisher<Void> unregister(Group group);
    }

    @FunctionalInterface
    public interface Registrer {
        Publisher<Void> register(Group group);
    }

    private final Unregisterer unregisterer;
    private final Registrer registrer;

    RegisteredGroupsSubscriber(Unregisterer unregisterer, Registrer registrer) {
        this.unregisterer = unregisterer;
        this.registrer = registrer;
    }

    @Override
    public void handle(Event event) {
        if (event instanceof RegisteredGroupListenerChangeEvent) {
            RegisteredGroupListenerChangeEvent changeEvent = (RegisteredGroupListenerChangeEvent) event;

            Mono<List<Group>> registeredGroupsMono = Flux.fromIterable(((RegisteredGroupListenerChangeEvent) event).getRegisteredGroups()).collectList();

            registeredGroupsMono.flatMapMany(registeredGroups -> Flux.concat(
                    unbindUnrequiredRegisteredGroups(changeEvent),
                    bindRequiredUnregisteredGroups(changeEvent)))
                .then()
                .block();
        }
    }

    private Mono<Void> unbindUnrequiredRegisteredGroups(RegisteredGroupListenerChangeEvent changeEvent) {
        return Flux.fromIterable(Sets.difference(changeEvent.getRegisteredGroups(), changeEvent.getRequiredGroups()))
            .concatMap(unregisterer::unregister)
            .then();
    }

    private Mono<Void> bindRequiredUnregisteredGroups(RegisteredGroupListenerChangeEvent changeEvent) {
        return Flux.fromIterable(Sets.difference(changeEvent.getRequiredGroups(), changeEvent.getRegisteredGroups()))
            .concatMap(registrer::register)
            .then();
    }
}
