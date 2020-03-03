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

package org.apache.james.jmap;

public interface HttpConstants {
    String JSON_CONTENT_TYPE = "application/json";
    String JSON_CONTENT_TYPE_UTF8 = "application/json; charset=UTF-8";
    String CONTENT_TYPE = "ContentType";
    String ACCEPT = "Accept";


    int SC_OK = 200;
    int SC_CREATED = 201;
    int SC_NO_CONTENT = 204;
    int SC_BAD_REQUEST = 400;
    int SC_UNAUTHORIZED = 401;
    int SC_FORBIDDEN = 403;
    int SC_INTERNAL_SERVER_ERROR = 500;
}
