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

package org.apache.james.modules;

import org.apache.james.modules.blobstore.BlobStoreChoosingConfiguration;
import org.apache.james.modules.objectstorage.aws.s3.DockerAwsS3TestRule;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.util.Modules;

public class TestAwsS3BlobStoreModule extends AbstractModule {

    private final DockerAwsS3TestRule dockerAwsS3TestRule;

    public TestAwsS3BlobStoreModule(DockerAwsS3TestRule awsS3TestRule) {
        this.dockerAwsS3TestRule = awsS3TestRule;
    }

    @Override
    protected void configure() {
        Module testAwsS3BlobStoreModule = Modules
            .override(dockerAwsS3TestRule.getModule())
            .with(binder -> binder.bind(BlobStoreChoosingConfiguration.class)
                    .toInstance(BlobStoreChoosingConfiguration.objectStorage()));

        install(testAwsS3BlobStoreModule);
    }
}

