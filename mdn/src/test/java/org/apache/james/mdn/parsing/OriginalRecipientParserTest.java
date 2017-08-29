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

package org.apache.james.mdn.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.mdn.fields.AddressType;
import org.apache.james.mdn.fields.OriginalRecipient;
import org.apache.james.mdn.fields.Text;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class OriginalRecipientParserTest {

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private OriginalRecipientParser lenient;
    private OriginalRecipientParser strict;

    @Before
    public void setUp() {
        lenient = new OriginalRecipientParser(false);
        strict = new OriginalRecipientParser(true);
    }

    @Test
    public void parseStrictShouldRetrieveValue() {
        assertThat(strict.parse("rfc822; a@address.com"))
            .contains(new OriginalRecipient(Text.fromRawText("a@address.com")));
    }

    @Test
    public void parseStrictShouldAcceptCustomType() {
        assertThat(strict.parse("postal; 5 rue st Coincoin"))
            .contains(new OriginalRecipient(new AddressType("postal"), Text.fromRawText("5 rue st Coincoin")));
    }

    @Test
    public void parseStrictShouldAcceptBreakLineInValue() {
        assertThat(strict.parse("postal; 5 rue st Coincoin\n35421 St Piou piou\nFrance"))
            .contains(new OriginalRecipient(new AddressType("postal"), Text.fromRawText("5 rue st Coincoin\n35421 St Piou piou\nFrance")));
    }

    @Test
    public void parseStrictShouldThrowOnMissingType() {
        expectedException.expect(IllegalArgumentException.class);

        strict.parse("a@address.com");
    }

    @Test
    public void parseStrictShouldThrowOnLineBreakInType() {
        expectedException.expect(IllegalArgumentException.class);

        strict.parse("a\nb;a@address.com");
    }

    @Test
    public void parseStrictShouldThrowOnEmptyType() {
        expectedException.expect(IllegalArgumentException.class);

        strict.parse("; a@any");
    }

    @Test
    public void parseLenientShouldAcceptNoType() {
        assertThat(lenient.parse("a@address.com"))
            .contains(new OriginalRecipient(Text.fromRawText("a@address.com")));
    }

    @Test
    public void parseLenientShouldAcceptTypesWithLineBreaks() {
        assertThat(lenient.parse("a\nb;a@address.com"))
            .contains(new OriginalRecipient(Text.fromRawText("a@address.com")));
    }

    @Test
    public void parseLenientShouldAcceptEmptyTypes() {
        assertThat(lenient.parse("; a@any"))
            .contains(new OriginalRecipient(Text.fromRawText("a@any")));
    }

    @Test
    public void parseLenientShouldFilterEmpty() {
        assertThat(lenient.parse(""))
            .isEmpty();
    }

    @Test
    public void parseLenientShouldFilterFoldingWhiteSpaces() {
        assertThat(lenient.parse("  "))
            .isEmpty();
    }

    @Test
    public void parseStrictShouldFilterEmpty() {
        expectedException.expect(IllegalArgumentException.class);

        strict.parse("");
    }

    @Test
    public void parseStrictShouldFilterFoldingWhiteSpaces() {
        expectedException.expect(IllegalArgumentException.class);

        strict.parse("  ");
    }
}
