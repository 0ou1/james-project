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
package org.apache.james.webadmin.dto;

import java.time.Instant;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.james.json.DTOModule;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.mailbox.tools.indexer.UserReindexingTask;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class WebAdminUserReindexingTaskAdditionalInformationDTO implements AdditionalInformationDTO {

    public static AdditionalInformationDTOModule<UserReindexingTask.AdditionalInformation, WebAdminUserReindexingTaskAdditionalInformationDTO> serializationModule(MailboxId.Factory factory) {
        return DTOModule.forDomainObject(UserReindexingTask.AdditionalInformation.class)
            .convertToDTO(WebAdminUserReindexingTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(dto -> {
                throw new NotImplementedException("Deserialization not implemented for this DTO");
            })
            .toDTOConverter((details, type) -> new WebAdminUserReindexingTaskAdditionalInformationDTO(
                type,
                details.getUsername(),
                details.getSuccessfullyReprocessedMailCount(),
                details.getFailedReprocessedMailCount(),
                SerializableReIndexingExecutionFailures.from(details.failures()),
                details.timestamp()))
            .typeName(UserReindexingTask.USER_RE_INDEXING.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private final WebAdminReprocessingContextInformationDTO reprocessingContextInformationDTO;
    private final String username;

    @JsonCreator
    private WebAdminUserReindexingTaskAdditionalInformationDTO(@JsonProperty("type") String type,
                                                               @JsonProperty("username") String username,
                                                               @JsonProperty("successfullyReprocessedMailCount") int successfullyReprocessedMailCount,
                                                               @JsonProperty("failedReprocessedMailCount") int failedReprocessedMailCount,
                                                               @JsonProperty("failures") SerializableReIndexingExecutionFailures failures,
                                                               @JsonProperty("timestamp") Instant timestamp) {
        this.username = username;
        this.reprocessingContextInformationDTO = new WebAdminReprocessingContextInformationDTO(
            type,
            successfullyReprocessedMailCount,
            failedReprocessedMailCount, failures, timestamp);
    }

    @Override
    public String getType() {
        return reprocessingContextInformationDTO.getType();
    }

    public Instant getTimestamp() {
        return reprocessingContextInformationDTO.getTimestamp();
    }

    public String getUsername() {
        return username;
    }

    public int getSuccessfullyReprocessedMailCount() {
        return reprocessingContextInformationDTO.getSuccessfullyReprocessedMailCount();
    }

    public int getFailedReprocessedMailCount() {
        return reprocessingContextInformationDTO.getFailedReprocessedMailCount();
    }

    public SerializableReIndexingExecutionFailures getFailures() {
        return reprocessingContextInformationDTO.getFailures();
    }
}
