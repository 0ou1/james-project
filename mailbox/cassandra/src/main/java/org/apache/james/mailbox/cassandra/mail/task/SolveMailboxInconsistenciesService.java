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

import static org.apache.james.mailbox.cassandra.mail.migration.MailboxPathV2Migration.MAILBOX_PATH_V_2_MIGRATION_PERFORMED_VERSION;

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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class SolveMailboxInconsistenciesService {
    public static final Logger LOGGER = LoggerFactory.getLogger(SolveMailboxInconsistenciesService.class);

    interface Inconsistency {
        Mono<Result> fix(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO);
    }

    private static class NoInconsistency implements Inconsistency{
        @Override
        public Mono<Result> fix(Context context, CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO pathV2DAO) {
            return Mono.just(Result.COMPLETED);
        }
    }

    /**
     * The Mailbox is referenced in MailboxDAO but the corresponding
     * reference is missing in MailboxPathDao.
     *
     * In order to solve this inconsistency, we can simple re-reference the mailboxPath.
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
                        LOGGER.info("Fixing inconsistency for orphan mailbox {} - {}",
                            mailbox.getMailboxId().serialize(),
                            mailbox.generateAssociatedPath().asString());
                        context.fixedInconsistencies.incrementAndGet();
                        return Result.COMPLETED;
                    } else {
                        context.errors.incrementAndGet();
                        LOGGER.warn("Fail fixing inconsistency for orphan mailbox {} - {}",
                            mailbox.getMailboxId().serialize(),
                            mailbox.generateAssociatedPath().asString());
                        return Result.PARTIAL;
                    }
                });
        }
    }

    /**
     * The Mailbox is referenced in MailboxPathDao but the corresponding
     * entry is missing in MailboxDao.
     *
     * CassandraIds are guaranteed to be unique, and are immutable once set to a mailbox.
     *
     * This this inconsistency arise if mailbox creation fails or upon partial deletes.
     *
     * In both case removing the dandling path registration solves the inconsistency
     *
     * In order to solve this inconsistency, we can simple re-reference the mailboxPath.
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
                    LOGGER.info("Fixing inconsistency for orphan mailboxPath {} - {}",
                        pathRegistration.getCassandraId().serialize(),
                        pathRegistration.getMailboxPath().asString());
                    context.fixedInconsistencies.incrementAndGet();
                })
                .map(any -> Result.COMPLETED)
                .switchIfEmpty(Mono.just(Result.COMPLETED))
                .onErrorResume(e -> {
                    LOGGER.error("Fail fixing inconsistency for orphan mailboxPath {} - {}",
                        pathRegistration.getCassandraId().serialize(),
                        pathRegistration.getMailboxPath().asString(),
                        e);
                    context.errors.incrementAndGet();
                    return Mono.just(Result.PARTIAL);
                });
        }
    }

    /**
     * The Mailbox is referenced in MailboxDAO but the corresponding
     * reference is pointing to another mailbox in MailboxPathDao.
     *
     * This error can not be recovered as some data-loss might be involved. It is preferable to
     * ask the admin to review then merge the two mailbowes together using {@link MailboxMergingTask}.
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
                Context context = (Context) o;

                return Objects.equals(this.processedMailboxEntries.get(), context.processedMailboxEntries.get())
                    && Objects.equals(this.processedMailboxPathEntries.get(), context.processedMailboxPathEntries.get())
                    && Objects.equals(this.fixedInconsistencies.get(), context.fixedInconsistencies.get())
                    && Objects.equals(getConflictingEntries(), getConflictingEntries())
                    && Objects.equals(this.errors.get(), context.errors.get());
            }
            return false;
        }

        @Override
        public final int hashCode() {
            return Objects.hash(processedMailboxEntries.get(), processedMailboxPathEntries.get(), fixedInconsistencies.get(), getConflictingEntries(), errors.get());
        }
    }

    private final CassandraMailboxDAO mailboxDAO;
    private final CassandraMailboxPathV2DAO mailboxPathV2DAO;
    private final CassandraSchemaVersionDAO versionDAO;

    @Inject
    SolveMailboxInconsistenciesService(CassandraMailboxDAO mailboxDAO, CassandraMailboxPathV2DAO mailboxPathV2DAO, CassandraSchemaVersionDAO versionDAO) {
        this.mailboxDAO = mailboxDAO;
        this.mailboxPathV2DAO = mailboxPathV2DAO;
        this.versionDAO = versionDAO;
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
            "%s is required in order to ensure mailboxPathV2DAO to be correctly populated, got %s",
            MAILBOX_PATH_V_2_MIGRATION_PERFORMED_VERSION,
            maybeVersion);
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
            .flatMap(inconsistency -> inconsistency.fix(context, mailboxDAO, mailboxPathV2DAO))
            .doOnNext(any -> context.processedMailboxEntries.incrementAndGet());
    }

    private Mono<Inconsistency> detectInconsistency(Mailbox mailbox) {
        return mailboxPathV2DAO.retrieveId(mailbox.generateAssociatedPath())
            .map(pathRegistration -> {
                if (pathRegistration.getCassandraId().equals(mailbox.getMailboxId())) {
                    return new NoInconsistency();
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
                    return new NoInconsistency();
                }
                // Mailbox references another path
                return new ConflictingEntryInconsistency(mailbox, pathRegistration);
            })
            .switchIfEmpty(Mono.just(new OrphanMailboxPathDAOEntry(pathRegistration)));
    }
}
