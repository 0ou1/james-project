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

package org.apache.james;

import org.apache.james.mailbox.inmemory.InMemoryId;
import org.apache.james.modules.data.MemoryDataJmapModule;
import org.apache.james.modules.data.MemoryDataModule;
import org.apache.james.modules.mailbox.MemoryMailboxModule;
import org.apache.james.modules.server.JMXServerModule;
import org.apache.james.modules.server.MemoryMailQueueModule;
import org.apache.james.modules.server.QuotaModule;

import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import com.google.inject.util.Modules;

public class MemoryJamesServerMain {

    public static final TypeLiteral<InMemoryId> inMemoryId = new TypeLiteral<InMemoryId>(){};
    
    public static final Module inMemoryServerModule = Modules.combine(
        new MemoryDataModule(),
        new MemoryDataJmapModule(),
        new MemoryMailboxModule(),
        new QuotaModule(),
        new MemoryMailQueueModule<>(inMemoryId));

    public static void main(String[] args) throws Exception {
        new GuiceJamesServer<>(inMemoryId)
            .combineWith(inMemoryServerModule, new JMXServerModule())
            .start();
    }

}
