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

package org.apache.james.user.cassandra;

import org.apache.commons.configuration2.BaseHierarchicalConfiguration;
import org.apache.james.backends.cassandra.CassandraClusterExtension;
import org.apache.james.domainlist.api.DomainList;
import org.apache.james.user.lib.UsersRepositoryContract;
import org.apache.james.user.lib.UsersRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.RegisterExtension;

class CassandraUsersRepositoryTest {

    @RegisterExtension
    static CassandraClusterExtension cassandraCluster = new CassandraClusterExtension(CassandraUsersRepositoryModule.MODULE);

    @Nested
    class WhenEnableVirtualHosting implements UsersRepositoryContract.WithVirtualHostingIdempotentContract {
        @RegisterExtension
        UserRepositoryExtension extension = UserRepositoryExtension.withVirtualHost();

        private UsersRepositoryImpl usersRepository;

        @BeforeEach
        void setUp(TestSystem testSystem) throws Exception {
            usersRepository = getUsersRepository(testSystem.getDomainList(), extension.isSupportVirtualHosting());
        }

        @Override
        public UsersRepositoryImpl testee() {
            return usersRepository;
        }
    }

    @Nested
    class WhenDisableVirtualHosting implements UsersRepositoryContract.WithOutVirtualHostingIdempotentContract {
        @RegisterExtension
        UserRepositoryExtension extension = UserRepositoryExtension.withoutVirtualHosting();

        private UsersRepositoryImpl usersRepository;

        @BeforeEach
        void setUp(TestSystem testSystem) throws Exception {
            usersRepository = getUsersRepository(testSystem.getDomainList(), extension.isSupportVirtualHosting());
        }

        @Override
        public UsersRepositoryImpl testee() {
            return usersRepository;
        }
    }

    private static UsersRepositoryImpl getUsersRepository(DomainList domainList, boolean enableVirtualHosting) throws Exception {
        CassandraUsersDAO usersDAO = new CassandraUsersDAO(cassandraCluster.getCassandraCluster().getConf());
        BaseHierarchicalConfiguration configuration = new BaseHierarchicalConfiguration();
        configuration.addProperty("enableVirtualHosting", String.valueOf(enableVirtualHosting));

        UsersRepositoryImpl usersRepository = new UsersRepositoryImpl(domainList, usersDAO);
        usersRepository.configure(configuration);
        return usersRepository;
    }
}
