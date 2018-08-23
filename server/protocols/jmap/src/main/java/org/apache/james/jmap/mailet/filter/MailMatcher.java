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

package org.apache.james.jmap.mailet.filter;

import static org.apache.james.jmap.api.filtering.Rule.Condition;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.james.javax.AddressHelper;
import org.apache.james.jmap.api.filtering.Rule;
import org.apache.james.jmap.api.filtering.Rule.Condition.Field;
import org.apache.mailet.Mail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.functions.ThrowingFunction;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

public interface MailMatcher {

    interface HeaderExtractor extends ThrowingFunction<Mail, Stream<String>> {}

    class HeaderMatcher implements MailMatcher {

        private static final Logger LOGGER = LoggerFactory.getLogger(HeaderMatcher.class);

        private final ContentMatcher contentMatcher;
        private final String ruleValue;
        private final HeaderExtractor headerExtractor;

        HeaderMatcher(ContentMatcher contentMatcher, String ruleValue,
                      HeaderExtractor headerExtractor) {
            Preconditions.checkNotNull(contentMatcher);
            Preconditions.checkNotNull(headerExtractor);

            this.contentMatcher = contentMatcher;
            this.ruleValue = ruleValue;
            this.headerExtractor = headerExtractor;
        }

        @Override
        public boolean match(Mail mail) {
            try {
                Stream<String> headerLines = headerExtractor.apply(mail);
                return contentMatcher.match(headerLines, ruleValue);
            } catch (Exception e) {
                LOGGER.error("error while extracting mail header", e);
                return false;
            }
        }
    }

    interface ContentMatcher {

        class AddressHeader {
            private static final Logger LOGGER = LoggerFactory.getLogger(AddressHeader.class);

            private Optional<String> personal;
            private Optional<String> address;
            private final String fullAddress;

            private AddressHeader(String fullAddress) {
                this.fullAddress = fullAddress;
                parseFullAddress();
            }

            private void parseFullAddress() {
                try {
                    InternetAddress internetAddress = new InternetAddress(fullAddress);
                    this.personal = Optional.ofNullable(internetAddress.getPersonal());
                    this.address = Optional.ofNullable(internetAddress.getAddress());
                } catch (AddressException e) {
                    LOGGER.error("error while parsing full address {}", fullAddress, e);
                    this.personal = Optional.empty();
                    this.address = Optional.empty();
                }
            }

            public Optional<String> getPersonal() {
                return personal;
            }

            public Optional<String> getAddress() {
                return address;
            }

            public String getFullAddress() {
                return fullAddress;
            }
        }

        ContentMatcher STRING_CONTAINS_MATCHER = (contents, valueToMatch) -> contents.anyMatch(content -> StringUtils.contains(content, valueToMatch));
        ContentMatcher STRING_NOT_CONTAINS_MATCHER = negate(STRING_CONTAINS_MATCHER);
        ContentMatcher STRING_EXACTLY_EQUALS_MATCHER = (contents, valueToMatch) -> contents.anyMatch(content -> StringUtils.equals(content, valueToMatch));
        ContentMatcher STRING_NOT_EXACTLY_EQUALS_MATCHER = negate(STRING_EXACTLY_EQUALS_MATCHER);

        Map<Rule.Condition.Comparator, ContentMatcher> CONTENT_STRING_MATCHER_REGISTRY = ImmutableMap.<Rule.Condition.Comparator, ContentMatcher>builder()
                .put(Condition.Comparator.CONTAINS, STRING_CONTAINS_MATCHER)
                .put(Condition.Comparator.NOT_CONTAINS, STRING_NOT_CONTAINS_MATCHER)
                .put(Condition.Comparator.EXACTLY_EQUALS, STRING_EXACTLY_EQUALS_MATCHER)
                .put(Condition.Comparator.NOT_EXACTLY_EQUALS, STRING_NOT_EXACTLY_EQUALS_MATCHER)
                .build();

        ContentMatcher ADDRESS_CONTAINS_MATCHER = (contents, valueToMatch) -> contents
                .map(ContentMatcher::asAddressHeader)
                .anyMatch(addressHeader -> StringUtils.contains(addressHeader.getFullAddress(), valueToMatch));

        ContentMatcher ADDRESS_NOT_CONTAINS_MATCHER = negate(ADDRESS_CONTAINS_MATCHER);
        ContentMatcher ADDRESS_EXACTLY_EQUALS_MATCHER = (contents, valueToMatch) -> contents
                .map(ContentMatcher::asAddressHeader)
                .anyMatch(addressHeader ->
                        StringUtils.equals(addressHeader.getFullAddress(), valueToMatch)
                        || StringUtils.equals(addressHeader.getAddress().orElse(null), valueToMatch)
                        || StringUtils.equals(addressHeader.getPersonal().orElse(null), valueToMatch));

        ContentMatcher ADDRESS_NOT_EXACTLY_EQUALS_MATCHER = negate(ADDRESS_EXACTLY_EQUALS_MATCHER);

        Map<Rule.Condition.Comparator, ContentMatcher> HEADER_ADDRESS_MATCHER_REGISTRY = ImmutableMap.<Rule.Condition.Comparator, ContentMatcher>builder()
                .put(Condition.Comparator.CONTAINS, ADDRESS_CONTAINS_MATCHER)
                .put(Condition.Comparator.NOT_CONTAINS, ADDRESS_NOT_CONTAINS_MATCHER)
                .put(Condition.Comparator.EXACTLY_EQUALS, ADDRESS_EXACTLY_EQUALS_MATCHER)
                .put(Condition.Comparator.NOT_EXACTLY_EQUALS, ADDRESS_NOT_EXACTLY_EQUALS_MATCHER)
                .build();

        Map<Rule.Condition.Field, Map<Rule.Condition.Comparator, ContentMatcher>> CONTENT_MATCHER_REGISTRY = ImmutableMap.<Rule.Condition.Field, Map<Rule.Condition.Comparator, ContentMatcher>>builder()
                .put(Condition.Field.SUBJECT, CONTENT_STRING_MATCHER_REGISTRY)
                .put(Condition.Field.TO, HEADER_ADDRESS_MATCHER_REGISTRY)
                .put(Condition.Field.CC, HEADER_ADDRESS_MATCHER_REGISTRY)
                .put(Condition.Field.RECIPIENT, HEADER_ADDRESS_MATCHER_REGISTRY)
                .put(Condition.Field.FROM, HEADER_ADDRESS_MATCHER_REGISTRY)
                .build();

        static ContentMatcher negate(ContentMatcher contentMatcher) {
            return (Stream<String> contents, String valueToMatch) ->
                    !contentMatcher.match(contents, valueToMatch);
        }

        static Optional<ContentMatcher> asContentMatcher(Condition.Field field, Condition.Comparator comparator) {
            return Optional
                .ofNullable(CONTENT_MATCHER_REGISTRY.get(field))
                .map(matcherRegistry -> matcherRegistry.get(comparator));
        }

        static AddressHeader asAddressHeader(String addressAsString) {
            return new AddressHeader(addressAsString);
        }

        boolean match(Stream<String> contents, String valueToMatch);
    }

    HeaderExtractor SUBJECT_EXTRACTOR = mail ->
            Optional.ofNullable(mail.getMessage().getSubject())
                    .map(Stream::of)
                    .orElse(Stream.empty());
    HeaderExtractor RECIPIENT_EXTRACTOR =  mail -> addressExtractor(
            ArrayUtils.addAll(
                mail.getMessage().getRecipients(Message.RecipientType.TO),
                mail.getMessage().getRecipients(Message.RecipientType.CC)));

    HeaderExtractor FROM_EXTRACTOR = mail -> addressExtractor(mail.getMessage().getFrom());
    HeaderExtractor CC_EXTRACTOR = recipientExtractor(Message.RecipientType.CC);
    HeaderExtractor TO_EXTRACTOR = recipientExtractor(Message.RecipientType.TO);

    Map<Field, HeaderExtractor> HEADER_EXTRACTOR_REGISTRY = ImmutableMap.<Field, HeaderExtractor>builder()
            .put(Field.SUBJECT, SUBJECT_EXTRACTOR)
            .put(Field.RECIPIENT, RECIPIENT_EXTRACTOR)
            .put(Field.FROM, FROM_EXTRACTOR)
            .put(Field.CC, CC_EXTRACTOR)
            .put(Field.TO, TO_EXTRACTOR)
            .build();

    static MailMatcher from(Rule rule) {
        Condition ruleCondition = rule.getCondition();
        Optional<ContentMatcher> contentMatcherOptional = ContentMatcher.asContentMatcher(ruleCondition.getField(), ruleCondition.getComparator());
        Optional<HeaderExtractor> headerExtractorOptional = getHeaderExtractor(ruleCondition.getField());

        return new HeaderMatcher(
                contentMatcherOptional.orElse(null),
                rule.getCondition().getValue(),
                headerExtractorOptional.orElse(null));
    }

    static HeaderExtractor recipientExtractor(Message.RecipientType type) {
        return mail -> addressExtractor(mail.getMessage().getRecipients(type));
    }

    static Stream<String> addressExtractor(Address[] addresses) {
        return Optional.ofNullable(addresses)
                .map(AddressHelper::asStringStream)
                .orElse(Stream.empty());
    }

    static Optional<HeaderExtractor> getHeaderExtractor(Field field) {
        return Optional
            .ofNullable(HEADER_EXTRACTOR_REGISTRY.get(field));
    }

    boolean match(Mail mail);
}
