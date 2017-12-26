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

import java.util.List;

import org.apache.james.task.TaskManager;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.steveash.guavate.Guavate;

public class StatusReportDto {
    public static List<StatusReportDto> from(List<TaskManager.StatusReport> reports) {
        return reports.stream()
            .map(report -> new StatusReportDto(
                TaskIdDto.from(report.getTaskId()),
                report.getStatus().getValue(),
                report.getClazz().getCanonicalName()))
            .collect(Guavate.toImmutableList());
    }

    private final TaskIdDto id;
    private final String status;
    private final String clazz;

    public StatusReportDto(TaskIdDto id, String status, String clazz) {
        this.id = id;
        this.status = status;
        this.clazz = clazz;
    }

    public TaskIdDto getTaskId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    @JsonProperty("class")
    public String getClazz() {
        return clazz;
    }
}
