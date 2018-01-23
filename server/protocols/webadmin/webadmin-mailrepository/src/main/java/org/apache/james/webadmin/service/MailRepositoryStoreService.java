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

package org.apache.james.webadmin.service;

import java.util.List;
import java.util.Optional;

import javax.inject.Inject;
import javax.mail.MessagingException;

import org.apache.james.mailrepository.api.MailRepository;
import org.apache.james.mailrepository.api.MailRepositoryStore;
import org.apache.james.util.streams.Iterators;
import org.apache.james.util.streams.Limit;
import org.apache.james.webadmin.dto.MailKey;
import org.apache.james.webadmin.dto.MailRepositoryResponse;

import com.github.fge.lambdas.Throwing;
import com.github.fge.lambdas.functions.ThrowingFunction;
import com.github.steveash.guavate.Guavate;

public class MailRepositoryStoreService {
    private final MailRepositoryStore mailRepositoryStore;

    @Inject
    public MailRepositoryStoreService(MailRepositoryStore mailRepositoryStore) {
        this.mailRepositoryStore = mailRepositoryStore;
    }

    public List<MailRepositoryResponse> listMailRepositories() {
        return mailRepositoryStore.getUrls()
            .stream()
            .map(MailRepositoryResponse::new)
            .collect(Guavate.toImmutableList());
    }

    public Optional<List<MailKey>> listMails(String url, long offset, Limit limit) throws MailRepositoryStore.MailRepositoryStoreException, MessagingException {
        Optional<MailRepository> mailRepository = Optional.ofNullable(mailRepositoryStore.select(url));
        ThrowingFunction<MailRepository, List<MailKey>> list = repository -> list(repository, offset, limit);
        return mailRepository.map(Throwing.function(list).sneakyThrow());
    }

    private List<MailKey> list(MailRepository mailRepository, long offset, Limit limit) throws MessagingException {
        return limit.applyOnStream(
                Iterators.toStream(mailRepository.list())
                    .skip(offset))
                .map(MailKey::new)
                .collect(Guavate.toImmutableList());
    }

    public Optional<Long> size(String url) throws MailRepositoryStore.MailRepositoryStoreException, MessagingException {
        Optional<MailRepository> mailRepository = Optional.ofNullable(mailRepositoryStore.select(url));
        return mailRepository.map(Throwing.function(MailRepository::size).sneakyThrow());
    }
}
