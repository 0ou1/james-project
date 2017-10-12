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

package org.apache.james.jmap.utils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BinaryOperator;

import org.apache.james.jmap.model.Keyword;
import org.apache.james.jmap.model.Keywords;

import com.github.steveash.guavate.Guavate;
import com.google.common.collect.Sets;

public class KeywordsCombiner implements BinaryOperator<Keywords> {

    private Keywords.KeywordsFactory keywordsFactory;

    public KeywordsCombiner() {
        this.keywordsFactory = keywordsFactory = Keywords.factory()
            .filterImapNonExposedKeywords();
    }

    @Override
    public Keywords apply(Keywords keywords, Keywords keywords2) {
        return keywordsFactory
            .fromSet(Sets.union(
                union(keywords.getKeywords(), keywords2.getKeywords(), Keyword.DRAFT),
                intersect(keywords.getKeywords(), keywords2.getKeywords(), Keyword.DRAFT)));
    }

    public Set<Keyword> union(Set<Keyword> set1, Set<Keyword> set2, Keyword... exceptKeywords) {
        List<Keyword> exceptKeywordList = Arrays.asList(exceptKeywords);
        return Sets.union(set1, set2)
            .stream()
            .filter(keyword -> !exceptKeywordList.contains(keyword))
            .collect(Guavate.toImmutableSet());
    }

    public Set<Keyword> intersect(Set<Keyword> set1, Set<Keyword> set2, Keyword... forKeywords) {
        List<Keyword> acceptedKeywords = Arrays.asList(forKeywords);
        return Sets.intersection(set1, set2)
            .stream()
            .filter(acceptedKeywords::contains)
            .collect(Guavate.toImmutableSet());
    }
}
