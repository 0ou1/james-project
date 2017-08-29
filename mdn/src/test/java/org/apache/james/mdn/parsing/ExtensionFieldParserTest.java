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

import org.apache.james.mdn.fields.ExtensionField;
import org.junit.Before;
import org.junit.Test;

public class ExtensionFieldParserTest {

    public static final String NAME = "Name";
    private ExtensionFieldParser parser;

    @Before
    public void setUp() {
        parser = new ExtensionFieldParser(NAME);
    }

    @Test
    public void parseShouldAcceptEmptyValue() {
        assertThat(parser.parse(""))
            .contains(new ExtensionField(NAME, ""));
    }

    @Test
    public void parseShouldAcceptFoldingWhiteSpaceValue() {
        assertThat(parser.parse("  "))
            .contains(new ExtensionField(NAME, ""));
    }

    @Test
    public void parseShouldAcceptSimpleValue() {
        assertThat(parser.parse("aa"))
            .contains(new ExtensionField(NAME, "aa"));
    }

    @Test
    public void parseShouldTrimValue() {
        assertThat(parser.parse("  aa  "))
            .contains(new ExtensionField(NAME, "aa"));
    }

    @Test
    public void parseShouldAcceptLineBreaks() {
        assertThat(parser.parse("aa\nbb"))
            .contains(new ExtensionField(NAME, "aa\nbb"));
    }
}
