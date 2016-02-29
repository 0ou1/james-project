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

package org.apache.james.jmap.methods.cassandra;

import org.apache.james.backends.cassandra.EmbeddedCassandra;
import org.apache.james.jmap.JmapServer;
import org.apache.james.jmap.cassandra.CassandraJmapServer;
import org.apache.james.jmap.methods.GetMessagesMethodTest;
import org.apache.james.mailbox.elasticsearch.EmbeddedElasticSearch;
import org.junit.Rule;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

public class CassandraGetMessagesMethodTest extends GetMessagesMethodTest {

    private TemporaryFolder temporaryFolder = new TemporaryFolder();
    private EmbeddedElasticSearch embeddedElasticSearch = new EmbeddedElasticSearch();
    private EmbeddedCassandra cassandra = EmbeddedCassandra.createStartServer();
    private JmapServer jmapServer = new CassandraJmapServer(CassandraJmapServer.defaultOverrideModule(temporaryFolder, embeddedElasticSearch, cassandra));

    @Rule
    public RuleChain chain = RuleChain
        .outerRule(temporaryFolder)
        .around(embeddedElasticSearch)
        .around(jmapServer);

    @Override
    protected JmapServer getJmapServer() {
        return jmapServer;
    }

    @Override
    protected void await() {
        embeddedElasticSearch.awaitForElasticSearch();
    }
}
