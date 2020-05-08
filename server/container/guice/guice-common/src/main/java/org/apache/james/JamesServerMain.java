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

import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.server.core.configuration.Configuration;
import org.apache.james.utils.PropertiesProvider;

import com.google.common.collect.ImmutableList;
import com.google.inject.Module;
import com.google.inject.util.Modules;

public interface JamesServerMain {
    @FunctionalInterface
    interface ConfiguredModulesSupplier {
        Stream<Module> configuredModules(PropertiesProvider propertiesProvider) throws ConfigurationException;
    }

    static void main(Module... modules) throws Exception {
        Configuration configuration = Configuration.builder()
            .useWorkingDirectoryEnvProperty()
            .build();

        main(configuration, ImmutableList.copyOf(modules));
    }

    static void main(Configuration configuration, List<Module> baseModules) throws Exception {
        GuiceJamesServer server = GuiceJamesServer.forConfiguration(configuration)
            .combineWith(Modules.combine(baseModules));
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
