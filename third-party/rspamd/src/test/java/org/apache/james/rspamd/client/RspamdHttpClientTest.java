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

package org.apache.james.rspamd.client;

import static org.apache.james.rspamd.DockerRspamd.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;

import org.apache.james.junit.categories.Unstable;
import org.apache.james.rspamd.DockerRspamdExtension;
import org.apache.james.rspamd.exception.UnauthorizedException;
import org.apache.james.rspamd.model.AnalysisResult;
import org.apache.james.util.ClassLoaderUtils;
import org.apache.james.util.Port;
import org.apache.james.webadmin.WebAdminUtils;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.restassured.http.Header;
import io.restassured.specification.RequestSpecification;

@Tag(Unstable.TAG)
class RspamdHttpClientTest {
    private final static String SPAM_MESSAGE_PATH = "mail/spam/spam8.eml";
    private final static String HAM_MESSAGE_PATH = "mail/ham/ham1.eml";
    private final static String VIRUS_MESSAGE_PATH = "mail/attachment/inlineVirusTextAttachment.eml";
    private final static String NON_VIRUS_MESSAGE_PATH = "mail/attachment/inlineNonVirusTextAttachment.eml";

    @RegisterExtension
    static DockerRspamdExtension rspamdExtension = new DockerRspamdExtension();

    private byte[] spamMessage;
    private byte[] hamMessage;
    private byte[] virusMessage;
    private byte[] nonVirusMessage;

    @BeforeEach
    void setup() {
        spamMessage = ClassLoaderUtils.getSystemResourceAsByteArray(SPAM_MESSAGE_PATH);
        hamMessage = ClassLoaderUtils.getSystemResourceAsByteArray(HAM_MESSAGE_PATH);
        virusMessage = ClassLoaderUtils.getSystemResourceAsByteArray(VIRUS_MESSAGE_PATH);
        nonVirusMessage = ClassLoaderUtils.getSystemResourceAsByteArray(NON_VIRUS_MESSAGE_PATH);
    }

    @Test
    void checkMailWithWrongPasswordShouldThrowUnauthorizedExceptionException() {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), "wrongPassword", Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        assertThatThrownBy(() -> client.checkV2(new ByteArrayInputStream(spamMessage), RspamdHttpClient.Options.NONE).block())
            .hasMessage("{\"error\":\"Unauthorized\"}")
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void learnSpamWithWrongPasswordShouldThrowUnauthorizedExceptionException() {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), "wrongPassword", Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        assertThatThrownBy(() -> reportAsSpam(client, new ByteArrayInputStream(spamMessage)))
            .hasMessage("{\"error\":\"Unauthorized\"}")
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void learnHamWithWrongPasswordShouldThrowUnauthorizedExceptionException() {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), "wrongPassword", Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        assertThatThrownBy(() -> reportAsHam(client, new ByteArrayInputStream(spamMessage)))
            .hasMessage("{\"error\":\"Unauthorized\"}")
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void checkSpamMailUsingRspamdClientWithExactPasswordShouldReturnAnalysisResultAsSameAsUsingRawClient() {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), PASSWORD, Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        AnalysisResult analysisResult = client.checkV2(new ByteArrayInputStream(spamMessage), RspamdHttpClient.Options.NONE).block();
        assertThat(analysisResult.getAction()).isEqualTo(AnalysisResult.Action.REJECT);

        RequestSpecification rspamdApi = WebAdminUtils.spec(Port.of(rspamdExtension.dockerRspamd().getPort()));
        rspamdApi
            .header(new Header("Password", PASSWORD))
            .body(ClassLoader.getSystemResourceAsStream(SPAM_MESSAGE_PATH))
            .post("checkv2")
        .then()
            .statusCode(HttpStatus.OK_200)
            .body("action", is(analysisResult.getAction().getDescription()))
            .body("required_score", is(analysisResult.getRequiredScore()))
            .body("subject", is(nullValue()));
    }

    @Test
    void checkHamMailUsingRspamdClientWithExactPasswordShouldReturnAnalysisResultAsSameAsUsingRawClient() {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), PASSWORD, Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        AnalysisResult analysisResult = client.checkV2(new ByteArrayInputStream(hamMessage), RspamdHttpClient.Options.NONE).block();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(analysisResult.getAction()).isEqualTo(AnalysisResult.Action.NO_ACTION);
            softly.assertThat(analysisResult.getRequiredScore()).isEqualTo(14.0F);
            softly.assertThat(analysisResult.getDesiredRewriteSubject()).isEqualTo(Optional.empty());
            softly.assertThat(analysisResult.hasVirus()).isEqualTo(false);
        });

        RequestSpecification rspamdApi = WebAdminUtils.spec(Port.of(rspamdExtension.dockerRspamd().getPort()));
        rspamdApi
            .header(new Header("Password", PASSWORD))
            .body(ClassLoader.getSystemResourceAsStream(HAM_MESSAGE_PATH))
            .post("checkv2")
        .then()
            .statusCode(HttpStatus.OK_200)
            .body("action", is(analysisResult.getAction().getDescription()))
            .body("required_score", is(analysisResult.getRequiredScore()))
            .body("subject", is(nullValue()));
    }

    @Test
    void learnSpamMailUsingRspamdClientWithExactPasswordShouldWork() {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), PASSWORD, Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        assertThatCode(() -> client.reportAsSpam(new ByteArrayInputStream(spamMessage), RspamdHttpClient.Options.NONE).block())
            .doesNotThrowAnyException();
    }

    @Test
    void learnHamMailUsingRspamdClientWithExactPasswordShouldWork() {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), PASSWORD, Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        assertThatCode(() -> client.reportAsHam(new ByteArrayInputStream(hamMessage), RspamdHttpClient.Options.NONE).block())
            .doesNotThrowAnyException();
    }

    @Test
    void learnHamMShouldBeIdempotent() {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), PASSWORD, Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        client.reportAsHam(new ByteArrayInputStream(hamMessage)).block();
        assertThatCode(() -> client.reportAsHam(new ByteArrayInputStream(hamMessage)).block())
            .doesNotThrowAnyException();
    }

    @Test
    void learnSpamMShouldBeIdempotent() {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), PASSWORD, Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        client.reportAsSpam(new ByteArrayInputStream(spamMessage)).block();
        assertThatCode(() -> client.reportAsSpam(new ByteArrayInputStream(spamMessage)).block())
            .doesNotThrowAnyException();
    }

    @Test
    void checkVirusMailUsingRspamdClientWithExactPasswordShouldReturnHasVirus() {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), PASSWORD, Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        AnalysisResult analysisResult = client.checkV2(new ByteArrayInputStream(virusMessage), RspamdHttpClient.Options.NONE).block();
        assertThat(analysisResult.hasVirus()).isTrue();
    }

    @Test
    void checkNonVirusMailUsingRspamdClientWithExactPasswordShouldReturnHasNoVirus() {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), PASSWORD, Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        AnalysisResult analysisResult = client.checkV2(new ByteArrayInputStream(nonVirusMessage), RspamdHttpClient.Options.NONE).block();
        assertThat(analysisResult.hasVirus()).isFalse();
    }

    @Test
    void test() throws InterruptedException {
        RspamdClientConfiguration configuration = new RspamdClientConfiguration(rspamdExtension.getBaseUrl(), PASSWORD, Optional.empty());
        RspamdHttpClient client = new RspamdHttpClient(configuration);

        client.reportAsSpam(new ByteArrayInputStream(nonVirusMessage), Optional.of("bob@domain.tld")).block();
        client.reportAsHam(new ByteArrayInputStream(virusMessage), Optional.of("alice@domain.tld")).block();

        client.reportAsSpam(new ByteArrayInputStream(spamMessage), Optional.of("alice@domain.tld")).block();
        client.reportAsHam(new ByteArrayInputStream(hamMessage), Optional.of("bob@domain.tld")).block();


        AnalysisResult analysisResultBob = client.checkV2(new ByteArrayInputStream(nonVirusMessage), Optional.of("bob@domain.tld")).block();
        AnalysisResult analysisResultAlice = client.checkV2(new ByteArrayInputStream(nonVirusMessage), Optional.of("alice@domain.tld")).block();

        System.out.println(analysisResultBob);
        System.out.println(analysisResultAlice);

        Thread.sleep(2000000);

        assertThat(analysisResultBob.getAction()).isEqualTo(AnalysisResult.Action.NO_ACTION);
        assertThat(analysisResultAlice.getAction()).isEqualTo(AnalysisResult.Action.REJECT);
    }

    private void reportAsSpam(RspamdHttpClient client, InputStream inputStream) {
        client.reportAsSpam(inputStream, RspamdHttpClient.Options.NONE).block();
    }

    private void reportAsHam(RspamdHttpClient client, InputStream inputStream) {
        client.reportAsHam(inputStream, RspamdHttpClient.Options.NONE).block();
    }

}
