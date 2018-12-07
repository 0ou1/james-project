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

package org.apache.james.blob.objectstorage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.apache.commons.io.IOUtils;
import org.apache.james.blob.api.BlobId;
import org.apache.james.blob.api.BlobStore;
import org.apache.james.blob.api.ObjectStoreException;
import org.apache.james.blob.objectstorage.swift.SwiftKeystone2ObjectStorage;
import org.apache.james.blob.objectstorage.swift.SwiftKeystone3ObjectStorage;
import org.apache.james.blob.objectstorage.swift.SwiftTempAuthObjectStorage;
import org.apache.james.util.concurrent.NamedThreadFactory;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.domain.Location;
import org.jclouds.io.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;

public class ObjectStorageBlobsDAO implements BlobStore {
    private static final InputStream EMPTY_STREAM = new ByteArrayInputStream(new byte[0]);
    private static final Location DEFAULT_LOCATION = null;
    private static final Logger LOGGER = LoggerFactory.getLogger(ObjectStorageBlobsDAO.class);


    private final BlobId.Factory blobIdFactory;

    private final ContainerName containerName;
    private final org.jclouds.blobstore.BlobStore blobStore;
    private final PayloadCodec payloadCodec;
    private final Executor executor;

    ObjectStorageBlobsDAO(ContainerName containerName, BlobId.Factory blobIdFactory,
                          org.jclouds.blobstore.BlobStore blobStore, PayloadCodec payloadCodec) {
        this.blobIdFactory = blobIdFactory;
        this.containerName = containerName;
        this.blobStore = blobStore;
        this.payloadCodec = payloadCodec;
        this.executor = Executors.newCachedThreadPool(NamedThreadFactory.withClassName(getClass()));
    }

    public static ObjectStorageBlobsDAOBuilder.RequireContainerName builder(SwiftTempAuthObjectStorage.Configuration testConfig) {
        return SwiftTempAuthObjectStorage.daoBuilder(testConfig);
    }

    public static ObjectStorageBlobsDAOBuilder.RequireContainerName builder(SwiftKeystone2ObjectStorage.Configuration testConfig) {
        return SwiftKeystone2ObjectStorage.daoBuilder(testConfig);
    }

    public static ObjectStorageBlobsDAOBuilder.RequireContainerName builder(SwiftKeystone3ObjectStorage.Configuration testConfig) {
        return SwiftKeystone3ObjectStorage.daoBuilder(testConfig);
    }

    public CompletableFuture<ContainerName> createContainer(ContainerName name) {
        return CompletableFuture.supplyAsync(() -> blobStore.createContainerInLocation(DEFAULT_LOCATION, name.value()))
            .thenApply(created -> {
                if (!created) {
                    LOGGER.debug("{} already existed", name);
                }
                return name;
            });
    }

    @Override
    public CompletableFuture<BlobId> save(byte[] data) {
        HashingInputStream hashingInputStream = new HashingInputStream(Hashing.sha256(), new ByteArrayInputStream(data));
        readFully(hashingInputStream);
        BlobId blobId = blobIdFactory.from(hashingInputStream.hash().toString());

        Blob blob = blobStore.blobBuilder(blobId.asString())
            .payload(payloadCodec.write(data))
            .build();

        return save(blob)
            .thenApply(any -> blobId);
    }

    @Override
    public CompletableFuture<BlobId> save(InputStream data) {
        Preconditions.checkNotNull(data);

        BlobId tmpId = blobIdFactory.randomId();
        return save(data, tmpId)
            .thenCompose(id -> updateBlobId(tmpId, id));
    }

    private CompletableFuture<BlobId> updateBlobId(BlobId from, BlobId to) {
        String containerName = this.containerName.value();
        return CompletableFuture
            .supplyAsync(() -> blobStore.copyBlob(containerName, from.asString(), containerName, to.asString(), CopyOptions.NONE), executor)
            .thenAcceptAsync(any -> blobStore.removeBlob(containerName, from.asString()))
            .thenApply(any -> to);
    }

    private CompletableFuture<BlobId> save(InputStream data, BlobId id) {
        HashingInputStream hashingInputStream = new HashingInputStream(Hashing.sha256(), data);
        Payload payload = payloadCodec.write(hashingInputStream);
        Blob blob = blobStore.blobBuilder(id.asString()).payload(payload).build();

        return save(blob)
            .thenApply(any -> blobIdFactory.from(hashingInputStream.hash().toString()));
    }

    private CompletableFuture<String> save(Blob blob) {
        String containerName = this.containerName.value();
        return CompletableFuture
            .supplyAsync(() -> blobStore.putBlob(containerName, blob), executor);
    }

    @Override
    public CompletableFuture<byte[]> readBytes(BlobId blobId) {
        return CompletableFuture
            .supplyAsync(Throwing.supplier(() -> IOUtils.toByteArray(read(blobId))).sneakyThrow(), executor);
    }

    @Override
    public InputStream read(BlobId blobId) throws ObjectStoreException {
        Blob blob = blobStore.getBlob(containerName.value(), blobId.asString());

        try {
            if (blob != null) {
                return payloadCodec.read(blob.getPayload());
            } else {
                return EMPTY_STREAM;
            }
        } catch (IOException cause) {
            throw new ObjectStoreException(
                "Failed to readBytes blob " + blobId.asString(),
                cause);
        }

    }

    private void readFully(InputStream inputStream) {
        byte[] buffer = new byte[1024];
        try {
            while (inputStream.read(buffer) > 0) { }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteContainer() {
        blobStore.deleteContainer(containerName.value());
    }

    public PayloadCodec getPayloadCodec() {
        return payloadCodec;
    }
}
