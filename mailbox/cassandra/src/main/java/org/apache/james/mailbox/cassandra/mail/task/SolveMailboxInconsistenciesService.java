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

package org.apache.james.mailbox.cassandra.mail.task;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;

import org.apache.james.backends.cassandra.versions.CassandraSchemaVersionDAO;
import org.apache.james.backends.cassandra.versions.SchemaVersion;
import org.apache.james.mailbox.cassandra.ids.CassandraId;
import org.apache.james.mailbox.cassandra.mail.CassandraIdAndPath;
import org.apache.james.mailbox.cassandra.mail.CassandraMailboxDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraMailboxPathV2DAO;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.task.Task;
import org.apache.james.task.Task.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.steveash.guavate.Guavate;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class SolveMailboxInconsistenciesService {
    public static final Logger LOGGER = LoggerFactory.getLogger(SolveMailboxInconsistenciesService.class);
    private static final boolean INCONSISTENCY_STILL_PRESENT = true;
    // Delay of 2 * Cassandra TCP timeout to ensure concurrentWrite upon diagnostic had time to complete
    private static final Duration DEFAULT_GRACE_PERIOD = Duration.ofSeconds(10);

    interface Inconsistency {
        Mono<Result> fix(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO);

        Mono<Boolean> isStillPertinent(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO);
    }

    private static Inconsistency NO_INCONSISTENCY = new Inconsistency() {
        @Override
        public Mono<Result> fix(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO) {
            return Mono.just(Result.COMPLETED);
        }

        @Override
        public Mono<Boolean> isStillPertinent(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO) {
            return Mono.just(INCONSISTENCY_STILL_PRESENT);
        }
    };

    /**
     * The Mailbox is referenced in MailboxDAO but the corresponding
     * reference is missing in MailboxPathDao.
     *
     * In order to solve this inconsistency, we can simply re-reference the mailboxPath.
     */
    private static class OrphanMailboxDAOEntry implements Inconsistency {
        private final Mailbox mailbox;

        private OrphanMailboxDAOEntry(Mailbox mailbox) {
            this.mailbox = mailbox;
        }

        @Override
        public Mono<Result> fix(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO) {
            return pathV2DAO.save(mailbox.generateAssociatedPath(), (CassandraId) mailbox.getMailboxId())
                .map(success -> {
                    if (success) {
                        LOGGER.info("Inconsistency fixed for orphan mailbox {} - {}",
                            mailbox.getMailboxId().serialize(),
                            mailbox.generateAssociatedPath().asString());
                        context.fixedInconsistencies.incrementAndGet();
                        return Result.COMPLETED;
                    } else {
                        context.errors.incrementAndGet();
                        LOGGER.warn("Failed fixing inconsistency for orphan mailbox {} - {}",
                            mailbox.getMailboxId().serialize(),
                            mailbox.generateAssociatedPath().asString());
                        return Result.PARTIAL;
                    }
                });
        }

        /**
         * We validate:
         *  - That the mailbox record stil exists and that its path did not change
         *  - That no corresponding path record exist
         */
        @Override
        public Mono<Boolean> isStillPertinent(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO) {
            return mailboxDAO.retrieveMailbox((CassandraId) mailbox.getMailboxId())
                .filter(currentMailbox -> currentMailbox.generateAssociatedPath().equals(mailbox.generateAssociatedPath()))
                .flatMap(currentMailbox -> pathV2DAO.retrieveId(currentMailbox.generateAssociatedPath())
                    .map(entry -> !INCONSISTENCY_STILL_PRESENT)
                    .switchIfEmpty(Mono.just(INCONSISTENCY_STILL_PRESENT)))
                .filter(stillPresent -> stillPresent)
                .switchIfEmpty(Mono.fromCallable(() -> {
                    context.errors.incrementAndGet();
                    LOGGER.error("Concurrent modification performed while attempting to fix {} {} orphan mailbox entry. " +
                            "This can be due to a mailbox on the operation while performing the check.",
                        mailbox.generateAssociatedPath().asString(),
                        mailbox.getMailboxId().serialize());
                    return !INCONSISTENCY_STILL_PRESENT;
                }));
        }
    }

    /**
     * The Mailbox is referenced in MailboxPathDao but the corresponding
     * entry is missing in MailboxDao.
     *
     * CassandraIds are guaranteed to be unique, and are immutable once set to a mailbox.
     *
     * This inconsistency arise if mailbox creation fails or upon partial deletes.
     *
     * In both case removing the dandling path registration solves the inconsistency
     *
     * In order to solve this inconsistency, we can simply re-reference the mailboxPath.
     */
    private static class OrphanMailboxPathDAOEntry implements Inconsistency {
        private final CassandraIdAndPath pathRegistration;

        private OrphanMailboxPathDAOEntry(CassandraIdAndPath pathRegistration) {
            this.pathRegistration = pathRegistration;
        }

        @Override
        public Mono<Result> fix(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO) {
            return pathV2DAO.delete(pathRegistration.getMailboxPath())
                .doOnSuccess(any -> {
                    LOGGER.info("Inconsistency fixed for orphan mailboxPath {} - {}",
                        pathRegistration.getCassandraId().serialize(),
                        pathRegistration.getMailboxPath().asString());
                    context.fixedInconsistencies.incrementAndGet();
                })
                .map(any -> Result.COMPLETED)
                .switchIfEmpty(Mono.just(Result.COMPLETED))
                .onErrorResume(e -> {
                    LOGGER.error("Failed fixing inconsistency for orphan mailboxPath {} - {}",
                        pathRegistration.getCassandraId().serialize(),
                        pathRegistration.getMailboxPath().asString(),
                        e);
                    context.errors.incrementAndGet();
                    return Mono.just(Result.PARTIAL);
                });
        }

        /**
         * We validate:
         *  - That the path record stil exists and that its id did not change
         *  - That no corresponding mailbox record exist
         */
        @Override
        public Mono<Boolean> isStillPertinent(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO) {
            return pathV2DAO.retrieveId(pathRegistration.getMailboxPath())
                .filter(pathRegistration::equals)
                .flatMap(currentRegistration -> mailboxDAO.retrieveMailbox(currentRegistration.getCassandraId())
                    .map(entry -> !INCONSISTENCY_STILL_PRESENT)
                    .switchIfEmpty(Mono.just(INCONSISTENCY_STILL_PRESENT)))
                .filter(stillPresent -> stillPresent)
                .switchIfEmpty(Mono.fromCallable(() -> {
                    context.errors.incrementAndGet();
                    LOGGER.error("Concurrent modification performed while attempting to fix {} {} orphan path entry. " +
                        "This can be due to a mailbox on the operation while performing the check.",
                        pathRegistration.getMailboxPath().asString(),
                        pathRegistration.getCassandraId().serialize());
                    return !INCONSISTENCY_STILL_PRESENT;
                }));
        }
    }

    /**
     * The Mailbox is referenced in MailboxDAO but the corresponding
     * reference is pointing to another mailbox in MailboxPathDao.
     *
     * This error can not be recovered as some data-loss might be involved. It is preferable to
     * ask the admin to review then merge the two mailbowes together using {@link MailboxMergingTask}.
     *
     * See https://github.com/apache/james-project/blob/master/src/site/markdown/server/manage-webadmin.md#correcting-ghost-mailbox
     */
    private static class ConflictingEntryInconsistency implements Inconsistency {
        private final ConflictingEntry conflictingEntry;

        private ConflictingEntryInconsistency(Mailbox mailbox, CassandraIdAndPath pathRegistration) {
            boolean samePath = mailbox.generateAssociatedPath().equals(pathRegistration.getMailboxPath());
            boolean sameId = mailbox.getMailboxId().equals(pathRegistration.getCassandraId());

            Preconditions.checkState(samePath != sameId);

            this.conflictingEntry = ConflictingEntry.builder()
                .mailboxDaoEntry(mailbox)
                .mailboxPathDaoEntry(pathRegistration);
        }

        @Override
        public Mono<Result> fix(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO) {
            LOGGER.error("MailboxDAO contains mailbox {} {} which conflict with corresponding registration {} {}. " +
                "We recommend merging these mailboxes together to prevent mail data loss.",
                conflictingEntry.getMailboxDaoEntry().getMailboxId(), conflictingEntry.getMailboxDaoEntry().getMailboxPath(),
                conflictingEntry.getMailboxPathDaoEntry().getMailboxId(), conflictingEntry.getMailboxPathDaoEntry().getMailboxPath());
            context.conflictingEntries.add(conflictingEntry);
            return Mono.just(Result.PARTIAL);
        }

        /**
         * Skip validating conflict as no table level correction will be performed
         */
        @Override
        public Mono<Boolean> isStillPertinent(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO) {
            return Mono.just(true);
        }
    }

    static class Context {
        static class Builder {
            private Optional<Long> processedMailboxEntries;
            private Optional<Long> processedMailboxPathEntries;
            private Optional<Long> fixedInconsistencies;
            private ImmutableList.Builder<ConflictingEntry> conflictingEntries;
            private Optional<Long> errors;

            Builder() {
                processedMailboxPathEntries = Optional.empty();
                fixedInconsistencies = Optional.empty();
                conflictingEntries = ImmutableList.builder();
                errors = Optional.empty();
                processedMailboxEntries = Optional.empty();
            }

            public Builder processedMailboxEntries(long count) {
                processedMailboxEntries = Optional.of(count);
                return this;
            }

            public Builder processedMailboxPathEntries(long count) {
                processedMailboxPathEntries = Optional.of(count);
                return this;
            }

            public Builder fixedInconsistencies(long count) {
                fixedInconsistencies = Optional.of(count);
                return this;
            }

            public Builder addConflictingEntry(ConflictingEntry conflictingEntry) {
                conflictingEntries.add(conflictingEntry);
                return this;
            }

            public Builder errors(long count) {
                errors = Optional.of(count);
                return this;
            }

            public Context build() {
                return new Context(
                    processedMailboxEntries.orElse(0L),
                    processedMailboxPathEntries.orElse(0L),
                    fixedInconsistencies.orElse(0L),
                    conflictingEntries.build(),
                    errors.orElse(0L));
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        private final AtomicLong processedMailboxEntries;
        private final AtomicLong processedMailboxPathEntries;
        private final AtomicLong fixedInconsistencies;
        private final ConcurrentLinkedDeque<ConflictingEntry> conflictingEntries;
        private final AtomicLong errors;

        Context() {
            this(new AtomicLong(), new AtomicLong(), new AtomicLong(), ImmutableList.of(), new AtomicLong());
        }

        Context(long processedMailboxEntries, long processedMailboxPathEntries, long fixedInconsistencies, Collection<ConflictingEntry> conflictingEntries, long errors) {
            this(new AtomicLong(processedMailboxEntries),
                new AtomicLong(processedMailboxPathEntries),
                new AtomicLong(fixedInconsistencies),
                conflictingEntries,
                new AtomicLong(errors));
        }

        private Context(AtomicLong processedMailboxEntries, AtomicLong processedMailboxPathEntries, AtomicLong fixedInconsistencies, Collection<ConflictingEntry> conflictingEntries, AtomicLong errors) {
            this.processedMailboxEntries = processedMailboxEntries;
            this.processedMailboxPathEntries = processedMailboxPathEntries;
            this.fixedInconsistencies = fixedInconsistencies;
            this.conflictingEntries = new ConcurrentLinkedDeque<>(conflictingEntries);
            this.errors = errors;
        }

        long getProcessedMailboxEntries() {
            return processedMailboxEntries.get();
        }

        long getProcessedMailboxPathEntries() {
            return processedMailboxPathEntries.get();
        }

        long getFixedInconsistencies() {
            return fixedInconsistencies.get();
        }

        ImmutableList<ConflictingEntry> getConflictingEntries() {
            return ImmutableList.copyOf(conflictingEntries);
        }

        long getErrors() {
            return errors.get();
        }

        @Override
        public final boolean equals(Object o) {
            if (o instanceof Context) {
                Context that = (Context) o;

                return Objects.equals(this.processedMailboxEntries.get(), that.processedMailboxEntries.get())
                    && Objects.equals(this.processedMailboxPathEntries.get(), that.processedMailboxPathEntries.get())
                    && Objects.equals(this.fixedInconsistencies.get(), that.fixedInconsistencies.get())
                    && Objects.equals(this.getConflictingEntries(), that.getConflictingEntries())
                    && Objects.equals(this.errors.get(), that.errors.get());
            }
            return false;
        }

        @Override
        public final int hashCode() {
            return Objects.hash(processedMailboxEntries.get(), processedMailboxPathEntries.get(), fixedInconsistencies.get(), getConflictingEntries(), errors.get());
        }
    }

    private static final SchemaVersion MAILBOX_PATH_V_2_MIGRATION_PERFORMED_VERSION = new SchemaVersion(6);

    private final CassandraMailboxDAO mailboxDAO;
    private final CassandraMailboxPathV2DAO mailboxPathV2DAO;
    private final CassandraSchemaVersionDAO versionDAO;
    private final Duration gracePeriod;

    @Inject
    SolveMailboxInconsistenciesService(CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO mailboxPathV2DAO, CassandraSchemaVersionDAO versionDAO) {
        this(mailboxDAO, mailboxPathV2DAO, versionDAO, DEFAULT_GRACE_PERIOD);
    }

    @VisibleForTesting
    SolveMailboxInconsistenciesService(CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO mailboxPathV2DAO, CassandraSchemaVersionDAO versionDAO, Duration gracePeriod) {
        this.mailboxDAO = mailboxDAO;
        this.mailboxPathV2DAO = mailboxPathV2DAO;
        this.versionDAO = versionDAO;
        this.gracePeriod = gracePeriod;
    }

    Mono<Result> fixMailboxInconsistencies(Context context) {
        assertValidVersion();
        return Flux.merge(
                processMailboxDaoInconsistencies(context),
                processMailboxPathDaoInconsistencies(context))
            .reduce(Result.COMPLETED, Task::combine);
    }

    private void assertValidVersion() {
        Optional<SchemaVersion> maybeVersion = versionDAO.getCurrentSchemaVersion().block();

        boolean isVersionValid = maybeVersion
            .map(version -> version.isAfterOrEquals(MAILBOX_PATH_V_2_MIGRATION_PERFORMED_VERSION))
            .orElse(false);

        Preconditions.checkState(isVersionValid,
            "Schema version %s is required in order to ensure mailboxPathV2DAO to be correctly populated, got %s",
            MAILBOX_PATH_V_2_MIGRATION_PERFORMED_VERSION.getValue(),
            maybeVersion.map(SchemaVersion::getValue));
    }

    private Flux<Result> processMailboxPathDaoInconsistencies(Context context) {
        return mailboxPathV2DAO.listAll()
            .flatMap(this::detectInconsistency)
            .flatMap(inconsistency -> inconsistency.fix(context, mailboxDAO, mailboxPathV2DAO))
            .doOnNext(any -> context.processedMailboxPathEntries.incrementAndGet());
    }

    private Flux<Result> processMailboxDaoInconsistencies(Context context) {
        return mailboxDAO.retrieveAllMailboxes()
            .flatMap(this::detectInconsistency)
            .collect(Guavate.toImmutableList())
            // Wait to ensure concurrentWrite upon diagnostic had time to complete
            .delayElement(gracePeriod)
            .flatMapMany(Flux::fromIterable)
            .flatMap(inconsistency -> fixInconsistencyIfNeeded(context, inconsistency))
            .doOnNext(any -> context.processedMailboxEntries.incrementAndGet());
    }

    private Mono<Result> fixInconsistencyIfNeeded(Context context, Inconsistency inconsistency) {
        return inconsistency.isStillPertinent(context, mailboxDAO, mailboxPathV2DAO)
            .flatMap(pertinent -> {
                System.out.println("And finally pertinent " + pertinent);
                if (pertinent) {
                    return inconsistency.fix(context, mailboxDAO, mailboxPathV2DAO);
                }
                return Mono.just(Result.PARTIAL);
            });
    }

    private Mono<Inconsistency> detectInconsistency(Mailbox mailbox) {
        return mailboxPathV2DAO.retrieveId(mailbox.generateAssociatedPath())
            .map(pathRegistration -> {
                if (pathRegistration.getCassandraId().equals(mailbox.getMailboxId())) {
                    return NO_INCONSISTENCY;
                }
                // Path entry references another mailbox.
                return new ConflictingEntryInconsistency(mailbox, pathRegistration);
            })
            .switchIfEmpty(Mono.just(new OrphanMailboxDAOEntry(mailbox)));
    }

    private Mono<Inconsistency> detectInconsistency(CassandraIdAndPath pathRegistration) {
        return mailboxDAO.retrieveMailbox(pathRegistration.getCassandraId())
            .map(mailbox -> {
                if (mailbox.generateAssociatedPath().equals(pathRegistration.getMailboxPath())) {
                    return NO_INCONSISTENCY;
                }
                // Mailbox references another path
                return new ConflictingEntryInconsistency(mailbox, pathRegistration);
            })
            .switchIfEmpty(Mono.just(new OrphanMailboxPathDAOEntry(pathRegistration)));
    }
}
