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

package org.apache.james.modules.blobstore;

import static org.apache.james.modules.blobstore.BlobStoreChoosingConfiguration.readBlobStoreChoosingConfiguration;

import java.io.FileNotFoundException;
import java.util.stream.Stream;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.JamesServerMain;
import org.apache.james.backends.cassandra.components.CassandraModule;
import org.apache.james.backends.cassandra.init.configuration.InjectionNames;
import org.apache.james.blob.api.BlobStore;
import org.apache.james.blob.api.MetricableBlobStore;
import org.apache.james.blob.cassandra.cache.BlobStoreCache;
import org.apache.james.blob.cassandra.cache.CachedBlobStore;
import org.apache.james.blob.cassandra.cache.CassandraBlobCacheModule;
import org.apache.james.blob.cassandra.cache.CassandraBlobStoreCache;
import org.apache.james.blob.cassandra.cache.CassandraCacheConfiguration;
import org.apache.james.modules.mailbox.CassandraCacheSessionModule;
import org.apache.james.modules.mailbox.ConfigurationComponent;
import org.apache.james.utils.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;

public class BlobStoreCacheConfiguredModulesSupplier implements JamesServerMain.ConfiguredModulesSupplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlobStoreCacheConfiguredModulesSupplier.class);

    public static class CacheDisabledModule extends AbstractModule {
        @Provides
        @Named(MetricableBlobStore.BLOB_STORE_IMPLEMENTATION)
        @Singleton
        BlobStore provideBlobStore(@Named(CachedBlobStore.BACKEND) BlobStore blobStore) {
            return blobStore;
        }
    }

    public static class CacheEnabledModule extends AbstractModule  {
        @Override
        protected void configure() {
            bind(CassandraBlobStoreCache.class).in(Scopes.SINGLETON);
            bind(BlobStoreCache.class).to(CassandraBlobStoreCache.class);

            Multibinder.newSetBinder(binder(), CassandraModule.class, Names.named(InjectionNames.CACHE))
                .addBinding()
                .toInstance(CassandraBlobCacheModule.MODULE);
        }

        @Provides
        @Named(MetricableBlobStore.BLOB_STORE_IMPLEMENTATION)
        @Singleton
        BlobStore provideBlobStore(CachedBlobStore cachedBlobStore) {
            return cachedBlobStore;
        }

        @Provides
        @Singleton
        CassandraCacheConfiguration providesCacheConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException {
            try {
                Configuration configuration = propertiesProvider.getConfigurations(ConfigurationComponent.NAMES);
                return CassandraCacheConfiguration.from(configuration);
            } catch (FileNotFoundException e) {
                LOGGER.warn("Could not find " + ConfigurationComponent.NAME + " configuration file, using cassandra cache defaults");
                return CassandraCacheConfiguration.DEFAULT;
            }
        }
    }

    @Override
    public Stream<Module> configuredModules(PropertiesProvider propertiesProvider) throws ConfigurationException {
        BlobStoreChoosingConfiguration blobStoreChoosingConfiguration = readBlobStoreChoosingConfiguration(propertiesProvider);

        if (blobStoreChoosingConfiguration.isCacheEnabled()) {
            return Stream.of(new CassandraCacheSessionModule(), new CacheEnabledModule());
        }
        return Stream.of(new CacheDisabledModule());
    }
}
