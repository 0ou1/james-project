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
package org.apache.james.rrt.lib;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.mail.internet.AddressException;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.james.core.Domain;
import org.apache.james.core.User;
import org.apache.james.domainlist.api.DomainList;
import org.apache.james.domainlist.api.DomainListException;
import org.apache.james.lifecycle.api.Configurable;
import org.apache.james.rrt.api.RecipientRewriteTable;
import org.apache.james.rrt.api.RecipientRewriteTableException;
import org.apache.james.rrt.lib.Mapping.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.github.fge.lambdas.functions.ThrowingFunction;
import com.google.common.base.Preconditions;

public abstract class AbstractRecipientRewriteTable implements RecipientRewriteTable, Configurable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRecipientRewriteTable.class);

    // The maximum mappings which will process before throwing exception
    private int mappingLimit = 10;

    private boolean recursive = true;

    private DomainList domainList;

    @Inject
    public void setDomainList(DomainList domainList) {
        this.domainList = domainList;
    }

    @Override
    public void configure(HierarchicalConfiguration config) throws ConfigurationException {
        setRecursiveMapping(config.getBoolean("recursiveMapping", true));
        try {
            setMappingLimit(config.getInt("mappingLimit", 10));
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(e.getMessage());
        }
        doConfigure(config);
    }

    /**
     * Override to handle config
     */
    protected void doConfigure(HierarchicalConfiguration conf) throws ConfigurationException {
    }

    public void setRecursiveMapping(boolean recursive) {
        this.recursive = recursive;
    }

    /**
     * Set the mappingLimit
     * 
     * @param mappingLimit
     *            the mappingLimit
     * @throws IllegalArgumentException
     *             get thrown if mappingLimit smaller then 1 is used
     */
    public void setMappingLimit(int mappingLimit) throws IllegalArgumentException {
        if (mappingLimit < 1) {
            throw new IllegalArgumentException("The minimum mappingLimit is 1");
        }
        this.mappingLimit = mappingLimit;
    }

    @Override
    public Mappings getMappings(String user, Domain domain) throws ErrorMappingException, RecipientRewriteTableException {
        return getMappings(User.fromLocalPartWithDomain(user, domain), mappingLimit);
    }

    public Mappings getMappings(User user, int mappingLimit) throws ErrorMappingException, RecipientRewriteTableException {

        // We have to much mappings throw ErrorMappingException to avoid
        // infinity loop
        if (mappingLimit == 0) {
            throw new ErrorMappingException("554 Too many mappings to process");
        }

        Mappings targetMappings = mapAddress(user.getLocalPart(), user.getDomainPart().get());

        if (targetMappings.contains(Type.Error)) {
            throw new ErrorMappingException(targetMappings.getError().getErrorMessage());
        }

        try {
            return MappingsImpl.fromMappings(
                targetMappings.asStream()
                    .flatMap(Throwing.<Mapping, Stream<Mapping>>function(target -> convertAndRecurseMapping(user, target, mappingLimit)).sneakyThrow()));
        } catch (SkipMappingProcessingException e) {
            return MappingsImpl.empty();
        }
    }

    private Stream<Mapping> convertAndRecurseMapping(User user, Mapping associatedMapping, int remainingLoops) throws ErrorMappingException, RecipientRewriteTableException, SkipMappingProcessingException, AddressException {

        ThrowingFunction<String, Stream<Mapping>> function =
            (String stringMapping) -> convertAndRecurseMapping(associatedMapping.getType(), user, stringMapping, remainingLoops);

        return associatedMapping.apply(user.asMailAddress())
            .map(Throwing.function(function).sneakyThrow())
            .orElse(Stream.empty());
    }

    private Stream<Mapping> convertAndRecurseMapping(Type mappingType, User originalUser, String addressWithMappingApplied, int remainingLoops) throws ErrorMappingException, RecipientRewriteTableException {
        LOGGER.debug("Valid virtual user mapping {} to {}", originalUser, addressWithMappingApplied);

        Stream<Mapping> possibleResult = Stream.of(toMapping(addressWithMappingApplied, mappingType));
        if (!recursive) {
            return possibleResult;
        }

        User targetUser = User.fromUsername(addressWithMappingApplied)
            .withDefaultDomain(originalUser.getDomainPart().get());

        // Check if the returned mapping is the same as the input. If so we need to handle identity to avoid loops.
        if (originalUser.equals(targetUser)) {
            return mappingType.getIdentityMappingBehaviour()
                .handleIdentity(possibleResult);
        } else {
            return recurseMapping(possibleResult, targetUser, remainingLoops);
        }
    }

    private Stream<Mapping> recurseMapping(Stream<Mapping> possibleResult, User targetUser, int remainingLoops) throws ErrorMappingException, RecipientRewriteTableException {
        Mappings childMappings = getMappings(targetUser, remainingLoops - 1);

        if (childMappings.isEmpty()) {
            return possibleResult;
        } else {
            return childMappings.asStream();
        }
    }

    private Mapping toMapping(String mappedAddress, Type type) {
        switch (type) {
            case Forward:
            case Group:
                return MappingImpl.of(type, mappedAddress);
            case Regex:
            case Domain:
            case Error:
            case Address:
                return MappingImpl.address(mappedAddress);
        }
        throw new IllegalArgumentException("unhandled enum type");
    }

    @Override
    public void addRegexMapping(String user, Domain domain, String regex) throws RecipientRewriteTableException {
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new RecipientRewriteTableException("Invalid regex: " + regex, e);
        }

        MappingImpl mapping = MappingImpl.regex(regex);
        checkMapping(user, domain, mapping);
        LOGGER.info("Add regex mapping => {} for user: {} domain: {}", regex, user, domain.name());
        addMapping(user, domain, mapping);

    }

    @Override
    public void removeRegexMapping(String user, Domain domain, String regex) throws RecipientRewriteTableException {
        LOGGER.info("Remove regex mapping => {} for user: {} domain: {}", regex, user, domain.name());
        removeMapping(user, domain, MappingImpl.regex(regex));
    }

    @Override
    public void addAddressMapping(String user, Domain domain, String address) throws RecipientRewriteTableException {
        Mapping mapping = MappingImpl.address(address)
            .appendDomainIfNone(defaultDomainSupplier());

        checkHasValidAddress(mapping);
        checkMapping(user, domain, mapping);

        LOGGER.info("Add address mapping => {} for user: {} domain: {}", mapping, user, domain.name());
        addMapping(user, domain, mapping);
    }

    private Supplier<Domain> defaultDomainSupplier() throws RecipientRewriteTableException {
        return Throwing.supplier(() -> {
            try {
                return domainList.getDefaultDomain();
            } catch (DomainListException e) {
                throw new RecipientRewriteTableException("Unable to retrieve default domain", e);
            }
        }).sneakyThrow();
    }

    private void checkHasValidAddress(Mapping mapping) throws RecipientRewriteTableException {
        if (!mapping.asMailAddress().isPresent()) {
            throw new RecipientRewriteTableException("Invalid emailAddress: " + mapping);
        }
    }

    @Override
    public void removeAddressMapping(String user, Domain domain, String address) throws RecipientRewriteTableException {
        Mapping mapping = MappingImpl.address(address)
            .appendDomainIfNone(defaultDomainSupplier());

        LOGGER.info("Remove address mapping => {} for user: {} domain: {}", mapping, user, domain.name());
        removeMapping(user, domain, mapping);
    }

    @Override
    public void addErrorMapping(String user, Domain domain, String error) throws RecipientRewriteTableException {
        MappingImpl mapping = MappingImpl.error(error);

        checkMapping(user, domain, mapping);
        LOGGER.info("Add error mapping => {} for user: {} domain: {}", error, user, domain.name());
        addMapping(user, domain, mapping);

    }

    @Override
    public void removeErrorMapping(String user, Domain domain, String error) throws RecipientRewriteTableException {
        LOGGER.info("Remove error mapping => {} for user: {} domain: {}", error, user, domain.name());
        removeMapping(user, domain, MappingImpl.error(error));
    }

    @Override
    public Map<String, Mappings> getAllMappings() throws RecipientRewriteTableException {
        Map<String, Mappings> mappings = getAllMappingsInternal();

        LOGGER.debug("Retrieve all mappings. Mapping count: {}", mappings.size());
        return mappings;
    }

    @Override
    public void addAliasDomainMapping(Domain aliasDomain, Domain realDomain) throws RecipientRewriteTableException {
        LOGGER.info("Add domain mapping: {} => {}", aliasDomain, realDomain);
        addMapping(null, aliasDomain, MappingImpl.domain(realDomain));
    }

    @Override
    public void removeAliasDomainMapping(Domain aliasDomain, Domain realDomain) throws RecipientRewriteTableException {
        LOGGER.info("Remove domain mapping: {} => {}", aliasDomain, realDomain);
        removeMapping(null, aliasDomain, MappingImpl.domain(realDomain));
    }

    @Override
    public void addForwardMapping(String user, Domain domain, String address) throws RecipientRewriteTableException {
        Mapping mapping = MappingImpl.forward(address)
            .appendDomainIfNone(defaultDomainSupplier());

        checkHasValidAddress(mapping);
        checkMapping(user, domain, mapping);

        LOGGER.info("Add forward mapping => {} for user: {} domain: {}", mapping, user, domain.name());
        addMapping(user, domain, mapping);
    }

    @Override
    public void removeForwardMapping(String user, Domain domain, String address) throws RecipientRewriteTableException {
        Mapping mapping = MappingImpl.forward(address)
            .appendDomainIfNone(defaultDomainSupplier());

        LOGGER.info("Remove forward mapping => {} for user: {} domain: {}", mapping, user, domain.name());
        removeMapping(user, domain, mapping);
    }

    @Override
    public void addGroupMapping(String user, Domain domain, String address) throws RecipientRewriteTableException {
        Mapping mapping = MappingImpl.group(address)
            .appendDomainIfNone(defaultDomainSupplier());

        checkHasValidAddress(mapping);
        checkMapping(user, domain, mapping);

        LOGGER.info("Add forward mapping => {} for user: {} domain: {}", mapping, user, domain.name());
        addMapping(user, domain, mapping);
    }

    @Override
    public void removeGroupMapping(String user, Domain domain, String address) throws RecipientRewriteTableException {
        Mapping mapping = MappingImpl.group(address)
            .appendDomainIfNone(defaultDomainSupplier());

        LOGGER.info("Remove forward mapping => {} for user: {} domain: {}", mapping, user, domain.name());
        removeMapping(user, domain, mapping);
    }

    /**
     * Return a Map which holds all Mappings
     * 
     * @return Map
     */
    protected abstract Map<String, Mappings> getAllMappingsInternal() throws RecipientRewriteTableException;

    /**
     * Override to map virtual recipients to real recipients, both local and
     * non-local. Each key in the provided map corresponds to a potential
     * virtual recipient, stored as a <code>MailAddress</code> object.
     * 
     * Translate virtual recipients to real recipients by mapping a string
     * containing the address of the real recipient as a value to a key. Leave
     * the value <code>null<code>
     * if no mapping should be performed. Multiple recipients may be specified by delineating
     * the mapped string with commas, semi-colons or colons.
     * 
     * @param user
     *            the mapping of virtual to real recipients, as
     *            <code>MailAddress</code>es to <code>String</code>s.
     */
    protected abstract Mappings mapAddress(String user, Domain domain) throws RecipientRewriteTableException;

    private void checkMapping(String user, Domain domain, Mapping mapping) throws RecipientRewriteTableException {
        Mappings mappings = getUserDomainMappings(user, domain);
        if (mappings != null && mappings.contains(mapping)) {
            throw new RecipientRewriteTableException("Mapping " + mapping + " for user " + user + " domain " + domain + " already exist!");
        }
    }

    /**
     * Return user String for the given argument.
     * If give value is null, return a wildcard.
     * 
     * @param user the given user String
     * @return fixedUser the fixed user String
     */
    protected String getFixedUser(String user) {
        String sanitizedUser = Optional.ofNullable(user).orElse(WILDCARD);
        Preconditions.checkArgument(sanitizedUser.equals(WILDCARD) || !sanitizedUser.contains("@"));
        return sanitizedUser;
    }

    /**
     * Fix the domain for the given argument.
     * If give value is null, return a wildcard.
     */
    protected Domain getFixedDomain(Domain domain) {
        return Optional.ofNullable(domain).orElse(Domains.WILDCARD);
    }

}
