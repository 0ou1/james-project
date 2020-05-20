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
package org.apache.james.jmap.rfc8621.contract

import java.nio.charset.StandardCharsets
import java.util.Base64

import io.netty.handler.codec.http.HttpHeaderNames.ACCEPT
import io.restassured.RestAssured._
import io.restassured.authentication.PreemptiveBasicAuthScheme
import io.restassured.http.{ContentType, Header, Headers}
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import org.apache.http.HttpStatus
import org.apache.james.GuiceJamesServer
import org.apache.james.jmap.rfc8621.contract.EchoMethodContract._
import org.apache.james.jmap.rfc8621.contract.Fixture._
import org.apache.james.jmap.rfc8621.contract.tags.CategoryTags
import org.apache.james.utils.DataProbeImpl
import org.junit.jupiter.api.{BeforeEach, Tag, Test}

object EchoMethodContract {
  private val authScheme: PreemptiveBasicAuthScheme = new PreemptiveBasicAuthScheme
    authScheme.setUserName(BOB.asString())
    authScheme.setPassword(BOB_PASSWORD)

  private val REQUEST_OBJECT_WITH_UNSUPPORTED_METHOD: String =
    """{
      |  "using": [
      |    "urn:ietf:params:jmap:core"
      |  ],
      |  "methodCalls": [
      |    [
      |      "Core/echo",
      |      {
      |        "arg1": "arg1data",
      |        "arg2": "arg2data"
      |      },
      |      "c1"
      |    ],
      |    [
      |      "error",
      |      {
      |        "type": "Not implemented"
      |      },
      |      "notsupport"
      |    ]
      |  ]
      |}""".stripMargin

  private val RESPONSE_OBJECT_WITH_UNSUPPORTED_METHOD: String =
    """{
      |  "sessionState": "75128aab4b1b",
      |  "methodResponses": [
      |    [
      |      "Core/echo",
      |      {
      |        "arg1": "arg1data",
      |        "arg2": "arg2data"
      |      },
      |      "c1"
      |    ],
      |    [
      |      "error",
      |      {
      |        "type": "Not implemented"
      |      },
      |      "notsupport"
      |    ]
      |  ]
      |}""".stripMargin
}

trait EchoMethodContract {

  @BeforeEach
  def setUp(server: GuiceJamesServer): Unit = {
    server.getProbe(classOf[DataProbeImpl])
      .fluent()
      .addDomain(DOMAIN.asString())
      .addUser(BOB.asString(), BOB_PASSWORD)

    requestSpecification = baseRequestSpecBuilder(server)
      .build
  }

  @Test
  @Tag(CategoryTags.BASIC_FEATURE)
  def echoMethodShouldRespondOKWithRFC8621VersionAndSupportedMethod(): Unit = {
    val authorizationValue: String =
      s"Basic ${toBase64("bob@domain.tld:bobpassword")}"

    val response: String = `given`()
        .headers(getHeadersWith(new Header("Authorization", authorizationValue)))
        .body(Fixture.ECHO_REQUEST_OBJECT)
      .when()
        .post()
      .then
        .statusCode(HttpStatus.SC_OK)
        .contentType(ContentType.JSON)
      .extract()
        .body()
        .asString()

    assertThatJson(response).isEqualTo(Fixture.ECHO_RESPONSE_OBJECT)
  }

  @Test
  @Tag(CategoryTags.BASIC_FEATURE)
  def echoMethodShouldRespond401WithRFC8621VersionWhenWrongAuthentication(): Unit = {
    val authorizationValue: String =
      s"Basic ${toBase64("alice@@domain.tld:bobpassword")}"

    `given`()
        .headers(getHeadersWith(new Header("Authorization", authorizationValue)))
        .body(Fixture.ECHO_REQUEST_OBJECT)
      .when()
        .post()
      .then
        .statusCode(HttpStatus.SC_UNAUTHORIZED)
  }

  @Test
  def echoMethodShouldRespondWithRFC8621VersionAndUnsupportedMethod(): Unit = {
    val authorizationValue: String =
      s"Basic ${toBase64("bob@domain.tld:bobpassword")}"

    val response: String = `given`()
        .headers(getHeadersWith(new Header("Authorization", authorizationValue)))
        .body(REQUEST_OBJECT_WITH_UNSUPPORTED_METHOD)
      .when()
        .post()
      .then
        .statusCode(HttpStatus.SC_OK)
        .contentType(ContentType.JSON)
      .extract()
        .body()
        .asString()

    assertThatJson(response).isEqualTo(RESPONSE_OBJECT_WITH_UNSUPPORTED_METHOD)
  }

  private def getHeadersWith(authHeader: Header): Headers = {
    new Headers(
      new Header(ACCEPT.toString, Fixture.ACCEPT_RFC8621_VERSION_HEADER),
      authHeader
    )
  }

  private def toBase64(stringValue: String): String = {
    Base64.getEncoder.encodeToString(stringValue.getBytes(StandardCharsets.UTF_8))
  }
}
