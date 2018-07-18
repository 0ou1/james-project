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

package org.apache.james.mailbox.cassandra.modules;

import static com.datastax.driver.core.DataType.bigint;
import static com.datastax.driver.core.DataType.text;
import static com.datastax.driver.core.DataType.timeuuid;

import org.apache.james.backends.cassandra.components.CassandraModule;
import org.apache.james.backends.cassandra.init.CassandraModuleComposite;
import org.apache.james.backends.cassandra.utils.CassandraConstants;
import org.apache.james.mailbox.cassandra.table.CassandraACLTable;
import org.apache.james.mailbox.cassandra.table.CassandraUserMailboxRightsTable;

import com.datastax.driver.core.schemabuilder.SchemaBuilder;

public interface CassandraAclModule{
    CassandraModule CASSANDRA_ACL_TABLE = CassandraModule.forTable(
        CassandraACLTable.TABLE_NAME,
        SchemaBuilder.createTable(CassandraACLTable.TABLE_NAME)
            .ifNotExists()
            .addPartitionKey(CassandraACLTable.ID, timeuuid())
            .addColumn(CassandraACLTable.ACL, text())
            .addColumn(CassandraACLTable.VERSION, bigint())
            .withOptions()
            .comment("Holds mailbox ACLs")
            .caching(SchemaBuilder.KeyCaching.ALL,
                SchemaBuilder.rows(CassandraConstants.DEFAULT_CACHED_ROW_PER_PARTITION)));

    CassandraModule CASSANDRA_USER_MAILBOX_RIGHTS_TABLE = CassandraModule.forTable(
        CassandraUserMailboxRightsTable.TABLE_NAME,
        SchemaBuilder.createTable(CassandraUserMailboxRightsTable.TABLE_NAME)
            .ifNotExists()
            .addPartitionKey(CassandraUserMailboxRightsTable.USER_NAME, text())
            .addClusteringColumn(CassandraUserMailboxRightsTable.MAILBOX_ID, timeuuid())
            .addColumn(CassandraUserMailboxRightsTable.RIGHTS, text())
            .withOptions()
            .compactionOptions(SchemaBuilder.leveledStrategy())
            .caching(SchemaBuilder.KeyCaching.ALL,
                SchemaBuilder.rows(CassandraConstants.DEFAULT_CACHED_ROW_PER_PARTITION))
            .comment("Denormalisation table. Allow to retrieve non personal mailboxIds a user has right on"));

    CassandraModule MODULE = new CassandraModuleComposite(
        CASSANDRA_ACL_TABLE,
        CASSANDRA_USER_MAILBOX_RIGHTS_TABLE);
}
