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

package org.apache.james.webadmin.integration;

import static com.jayway.restassured.RestAssured.given;
import static com.jayway.restassured.RestAssured.when;
import static com.jayway.restassured.config.EncoderConfig.encoderConfig;
import static com.jayway.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import org.apache.james.CassandraJamesServerMain;
import org.apache.james.GuiceJamesServer;
import org.apache.james.backends.cassandra.EmbeddedCassandra;
import org.apache.james.mailbox.elasticsearch.EmbeddedElasticSearch;
import org.apache.james.modules.CassandraJmapServerModule;
import org.apache.james.webadmin.routes.DomainRoutes;
import org.apache.james.webadmin.routes.UserRoutes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import com.google.common.base.Charsets;
import com.jayway.restassured.RestAssured;
import com.jayway.restassured.http.ContentType;
import com.jayway.restassured.parsing.Parser;

public class WebAdminServerIntegrationTest {

    public static final String DOMAIN = "domain";
    public static final String USERNAME = "username@" + DOMAIN;

    private TemporaryFolder temporaryFolder = new TemporaryFolder();
    private EmbeddedElasticSearch embeddedElasticSearch = new EmbeddedElasticSearch(temporaryFolder);
    private EmbeddedCassandra cassandra = EmbeddedCassandra.createStartServer();

    @Rule
    public RuleChain chain = RuleChain
        .outerRule(temporaryFolder)
        .around(embeddedElasticSearch);

    private GuiceJamesServer guiceJamesServer;

    @Before
    public void setUp() throws Exception {
        guiceJamesServer = new GuiceJamesServer()
            .combineWith(CassandraJamesServerMain.cassandraServerModule)
            .overrideWith(new CassandraJmapServerModule(temporaryFolder, embeddedElasticSearch, cassandra),
                new WebAdminConfigurationModule());
        guiceJamesServer.start();

        RestAssured.port = guiceJamesServer.getWebadminPort().orElseThrow(() -> new RuntimeException("Unable to locate Web Admin port"));
        RestAssured.config = newConfig().encoderConfig(encoderConfig().defaultContentCharset(Charsets.UTF_8));
        RestAssured.defaultParser = Parser.JSON;
    }

    @After
    public void tearDown() {
        guiceJamesServer.stop();
    }

    @Test
    public void postShouldAddTheGivenDomain() throws Exception {
        when()
            .put(DomainRoutes.DOMAINS + DOMAIN)
        .then()
            .statusCode(200);

        assertThat(guiceJamesServer.serverProbe().listDomains()).contains(DOMAIN);
    }

    @Test
    public void deleteShouldRemoveTheGivenDomain() throws Exception {
        guiceJamesServer.serverProbe().addDomain(DOMAIN);

        when()
            .delete(DomainRoutes.DOMAINS + DOMAIN)
        .then()
            .statusCode(200);

        assertThat(guiceJamesServer.serverProbe().listDomains()).doesNotContain(DOMAIN);
    }

    @Test
    public void postShouldAddTheUser() throws Exception {
        guiceJamesServer.serverProbe().addDomain(DOMAIN);

        given()
            .accept(ContentType.JSON)
            .contentType(ContentType.JSON)
            .body("{\"username\":\"" + USERNAME + "\",\"password\":\"password\"}")
        .when()
            .post(UserRoutes.USERS)
        .then()
            .statusCode(200);

        assertThat(guiceJamesServer.serverProbe().listUsers()).contains(USERNAME);
    }

    @Test
    public void deleteShouldRemoveTheUser() throws Exception {
        guiceJamesServer.serverProbe().addDomain(DOMAIN);
        guiceJamesServer.serverProbe().addUser(USERNAME, "anyPassword");

        given()
            .accept(ContentType.JSON)
            .contentType(ContentType.JSON)
            .body("{\"username\":\"" + USERNAME + "\",\"password\":\"password\"}")
        .when()
            .delete(UserRoutes.USERS + USERNAME)
        .then()
            .statusCode(200);

        assertThat(guiceJamesServer.serverProbe().listUsers()).doesNotContain(USERNAME);
    }

    @Test
    public void getUsersShouldDisplayUsers() throws Exception {
        guiceJamesServer.serverProbe().addDomain(DOMAIN);
        guiceJamesServer.serverProbe().addUser(USERNAME, "anyPassword");

        when()
            .get(UserRoutes.USERS)
        .then()
            .statusCode(200)
            .body(is("[\"username@domain\"]"));
    }

}
