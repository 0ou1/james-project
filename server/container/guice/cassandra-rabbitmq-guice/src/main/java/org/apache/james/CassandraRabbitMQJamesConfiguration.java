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

import java.util.Optional;

import org.apache.james.filesystem.api.JamesDirectoriesProvider;
import org.apache.james.modules.blobstore.BlobStoreConfiguration;
import org.apache.james.server.core.JamesServerResourceLoader;
import org.apache.james.server.core.configuration.Configuration;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.apache.james.utils.PropertiesProvider;

import com.github.fge.lambdas.Throwing;

public class CassandraRabbitMQJamesConfiguration implements Configuration {
    public static class Builder extends Configuration.Builder<Builder> {
        private Optional<BlobStoreConfiguration> blobStoreConfiguration;

        public Builder() {
            super();
            this.blobStoreConfiguration = Optional.empty();
        }

        public Builder blobStore(BlobStoreConfiguration blobStoreConfiguration) {
            this.blobStoreConfiguration = Optional.of(blobStoreConfiguration);
            return this;
        }

        public CassandraRabbitMQJamesConfiguration build() {
            String configurationPath = configurationPath();
            JamesServerResourceLoader directories = directories();

            return new CassandraRabbitMQJamesConfiguration(
                configurationPath,
                directories,
                blobStoreConfiguration.orElseGet(Throwing.supplier(
                    () -> BlobStoreConfiguration.parse(
                        new PropertiesProvider(new FileSystemImpl(directories), configurationPath)))));
        }
    }

    static CassandraRabbitMQJamesConfiguration.Builder builder() {
        return new Builder();
    }

    private final String configurationPath;
    private final JamesDirectoriesProvider directories;
    private final BlobStoreConfiguration blobStoreConfiguration;

    public CassandraRabbitMQJamesConfiguration(String configurationPath, JamesDirectoriesProvider directories, BlobStoreConfiguration blobStoreConfiguration) {
        this.configurationPath = configurationPath;
        this.directories = directories;
        this.blobStoreConfiguration = blobStoreConfiguration;
    }

    @Override
    public String configurationPath() {
        return configurationPath;
    }

    @Override
    public JamesDirectoriesProvider directories() {
        return directories;
    }

    public BlobStoreConfiguration blobstoreconfiguration() {
        return blobStoreConfiguration;
    }
}
