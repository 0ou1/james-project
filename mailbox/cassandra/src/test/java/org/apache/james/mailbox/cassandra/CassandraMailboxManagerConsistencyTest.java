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
package org.apache.james.mailbox.cassandra;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.backends.cassandra.CassandraCluster;
import org.apache.james.backends.cassandra.CassandraClusterExtension;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.cassandra.mail.MailboxAggregateModule;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.search.MailboxQuery;
import org.apache.james.mailbox.model.search.Wildcard;
import org.apache.james.mailbox.store.PreDeletionHooks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.fge.lambdas.Throwing;
import com.github.fge.lambdas.runnable.ThrowingRunnable;

class CassandraMailboxManagerConsistencyTest {

    private static final Username USER = Username.of("user");
    private static final String INBOX = "INBOX";
    private static final String INBOX_RENAMED = "INBOX_RENAMED";

    @RegisterExtension
    static CassandraClusterExtension cassandra = new CassandraClusterExtension(MailboxAggregateModule.MODULE_WITH_QUOTA);

    private CassandraMailboxManager testee;
    private MailboxSession mailboxSession;

    private MailboxPath inboxPath;
    private MailboxPath inboxPathRenamed;
    private MailboxQuery.UserBound allMailboxesSearchQuery;

    @BeforeEach
    void setUp(CassandraCluster cassandra) {
        testee = CassandraMailboxManagerProvider.provideMailboxManager(
            cassandra,
            PreDeletionHooks.NO_PRE_DELETION_HOOK);

        mailboxSession = testee.createSystemSession(USER);

        inboxPath = MailboxPath.forUser(USER, INBOX);
        inboxPathRenamed = MailboxPath.forUser(USER, INBOX_RENAMED);
        allMailboxesSearchQuery = MailboxQuery.builder()
            .userAndNamespaceFrom(inboxPath)
            .expression(Wildcard.INSTANCE)
            .build()
            .asUserBound();
    }

    @Nested
    class FailuresDuringCreation {

        @Test
        void createMailboxShouldBeConsistentWhenMailboxDaoFails(CassandraCluster cassandra) {
            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailbox (id,name,uidvalidity,mailboxbase) VALUES (:id,:name,:uidvalidity,:mailboxbase);")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.createMailbox(inboxPath, mailboxSession));

            cassandra.getConf().resetExecutionHook();

            SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                    .isEmpty();
                softly.assertThat(testee.list(mailboxSession))
                    .isEmpty();
            }));
        }

        @Test
        void createMailboxShouldBeConsistentWhenMailboxPathDaoFails(CassandraCluster cassandra) {
            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailboxPathV2")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.createMailbox(inboxPath, mailboxSession));

            cassandra.getConf().resetExecutionHook();

            SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                    .isEmpty();
                softly.assertThat(testee.list(mailboxSession))
                    .isEmpty();
            }));
        }

        @Disabled("JAMES-3056 createMailbox() doesn't return mailboxId while it's supposed to")
        @Test
        void createMailboxAfterAFailedCreationShouldCreateTheMailboxWhenMailboxDaoFails(CassandraCluster cassandra) throws Exception {
            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailbox (id,name,uidvalidity,mailboxbase) VALUES (:id,:name,:uidvalidity,:mailboxbase);")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.createMailbox(inboxPath, mailboxSession));

            assertThat(testee.createMailbox(inboxPath, mailboxSession))
                .isNotEmpty();
        }

        @Test
        void createMailboxAfterAFailedCreationShouldCreateTheMailboxWhenMailboxPathDaoFails(CassandraCluster cassandra) throws Exception {
            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailboxPathV2")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.createMailbox(inboxPath, mailboxSession));

            cassandra.getConf().resetExecutionHook();

            MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                .get();

            SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                    .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                        softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                        softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                    });
                softly.assertThat(testee.list(mailboxSession))
                    .containsExactly(inboxPath);
            }));
        }

        @Disabled("JAMES-3056 createMailbox() doesn't return mailboxId while it's supposed to")
        @Test
        void createMailboxAfterDeletingShouldCreateTheMailboxWhenMailboxDaoFails(CassandraCluster cassandra) throws Exception {
            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailbox (id,name,uidvalidity,mailboxbase) VALUES (:id,:name,:uidvalidity,:mailboxbase);")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.createMailbox(inboxPath, mailboxSession));

            cassandra.getConf().resetExecutionHook();

            doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

            assertThat(testee.createMailbox(inboxPath, mailboxSession))
                .isNotEmpty();
        }

        @Test
        void createMailboxAfterDeletingShouldCreateTheMailboxWhenMailboxPathDaoFails(CassandraCluster cassandra) throws Exception {
            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailboxPathV2")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.createMailbox(inboxPath, mailboxSession));

            cassandra.getConf().resetExecutionHook();

            doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

            MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                .get();

            SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                    .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                        softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                        softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                    });
                softly.assertThat(testee.list(mailboxSession))
                    .containsExactly(inboxPath);
            }));
        }
    }

    @Nested
    class FailuresDuringRenaming {

        @Test
        void renameShouldBeConsistentWhenMailboxDaoFails(CassandraCluster cassandra) throws Exception {
            MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                .get();

            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailbox (id,name,uidvalidity,mailboxbase) VALUES (:id,:name,:uidvalidity,:mailboxbase);")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.renameMailbox(inboxPath, inboxPathRenamed, mailboxSession));

            cassandra.getConf().resetExecutionHook();

            SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                    .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                        softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                        softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                    });
                softly.assertThat(testee.list(mailboxSession))
                    .containsExactly(inboxPath);
            }));
        }

        @Test
        void renameShouldBeConsistentWhenMailboxPathDaoFails(CassandraCluster cassandra) throws Exception {
            MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                .get();

            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailboxPathV2")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.renameMailbox(inboxPath, inboxPathRenamed, mailboxSession));

            cassandra.getConf().resetExecutionHook();

            SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                    .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                        softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                        softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                    });
                softly.assertThat(testee.list(mailboxSession))
                    .containsExactly(inboxPath);
            }));
        }

        @Disabled("JAMES-3056 cannot create a new mailbox because 'INBOX_RENAMED' already exists")
        @Test
        void createNewMailboxAfterAFailedRenameShouldCreateThatMailboxWhenMailboxDaoFails(CassandraCluster cassandra) throws Exception {
            MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                .get();

            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailbox (id,name,uidvalidity,mailboxbase) VALUES (:id,:name,:uidvalidity,:mailboxbase);")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.renameMailbox(inboxPath, inboxPathRenamed, mailboxSession));

            cassandra.getConf().resetExecutionHook();

            MailboxId newMailboxId = testee.createMailbox(inboxPathRenamed, mailboxSession)
                .get();

            SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                    .hasSize(2)
                    .anySatisfy(mailboxMetaData -> {
                        softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                        softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                    })
                    .anySatisfy(mailboxMetaData -> {
                        softly.assertThat(mailboxMetaData.getId()).isEqualTo(newMailboxId);
                        softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPathRenamed);
                    });
                softly.assertThat(testee.list(mailboxSession))
                    .containsExactlyInAnyOrder(inboxPath, inboxPathRenamed);
            }));
        }

        @Test
        void createNewMailboxAfterAFailedRenameShouldCreateThatMailboxWhenMailboxPathDaoFails(CassandraCluster cassandra) throws Exception {
            MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                .get();

            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailboxPathV2")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.renameMailbox(inboxPath, inboxPathRenamed, mailboxSession));

            cassandra.getConf().resetExecutionHook();

            MailboxId newMailboxId = testee.createMailbox(inboxPathRenamed, mailboxSession)
                .get();

            SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                    .hasSize(2)
                    .anySatisfy(mailboxMetaData -> {
                        softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                        softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                    })
                    .anySatisfy(mailboxMetaData -> {
                        softly.assertThat(mailboxMetaData.getId()).isEqualTo(newMailboxId);
                        softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPathRenamed);
                    });
                softly.assertThat(testee.list(mailboxSession))
                    .containsExactlyInAnyOrder(inboxPath, inboxPathRenamed);
            }));
        }

        @Disabled("JAMES-3056 creating mailbox returns an empty Optional")
        @Test
        void deleteMailboxAfterAFailedRenameShouldCreateThatMailboxWhenMailboxDaoFails(CassandraCluster cassandra) throws Exception {
            MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                .get();

            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailbox (id,name,uidvalidity,mailboxbase) VALUES (:id,:name,:uidvalidity,:mailboxbase);")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.renameMailbox(inboxPath, inboxPathRenamed, mailboxSession));

            cassandra.getConf().resetExecutionHook();

            testee.deleteMailbox(inboxId, mailboxSession);
            assertThat(testee.createMailbox(inboxPathRenamed, mailboxSession))
                .isNotEmpty();
        }

        @Test
        void deleteMailboxAfterAFailedRenameShouldCreateThatMailboxWhenMailboxPathDaoFails(CassandraCluster cassandra) throws Exception {
            MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                .get();

            cassandra.getConf()
                .fail()
                .whenBoundStatementStartsWith("INSERT INTO mailboxPathV2")
                .times(6)
                .setExecutionHook();

            doQuietly(() -> testee.renameMailbox(inboxPath, inboxPathRenamed, mailboxSession));

            cassandra.getConf().resetExecutionHook();

            testee.deleteMailbox(inboxId, mailboxSession);
            MailboxId newMailboxId = testee.createMailbox(inboxPathRenamed, mailboxSession)
                .get();

            SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                    .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                        softly.assertThat(mailboxMetaData.getId()).isEqualTo(newMailboxId);
                        softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPathRenamed);
                    });
                softly.assertThat(testee.list(mailboxSession))
                    .containsExactlyInAnyOrder(inboxPathRenamed);
            }));
        }
    }

    @Nested
    class FailuresOnDeletion {

        @Nested
        class DeleteOnce {
            @Disabled("JAMES-3056 allMailboxesSearchQuery returns empty list")
            @Test
            void deleteMailboxByPathShouldBeConsistentWhenMailboxDaoFails(CassandraCluster cassandra) throws Exception {
                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailbox WHERE id=:id;")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }

            @Disabled("JAMES-3056 allMailboxesSearchQuery returns empty list")
            @Test
            void deleteMailboxByIdShouldBeConsistentWhenMailboxDaoFails(CassandraCluster cassandra) throws Exception {
                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailbox WHERE id=:id;")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }

            @Test
            void deleteMailboxByPathShouldBeConsistentWhenMailboxPathDaoFails(CassandraCluster cassandra) throws Exception {
                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailboxPathV2")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }

            @Test
            void deleteMailboxByIdShouldBeConsistentWhenMailboxPathDaoFails(CassandraCluster cassandra) throws Exception {
                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailboxPathV2")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }
        }

        @Nested
        class DeleteOnceThenCreate {

            @Test
            void createMailboxShouldCreateWhenMailboxDaoFailsOnDeleteByPath(CassandraCluster cassandra) throws Exception {
                testee.createMailbox(inboxPath, mailboxSession);

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailbox WHERE id=:id;")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }

            @Test
            void createMailboxShouldCreateWhenMailboxDaoFailsOnDeleteById(CassandraCluster cassandra) throws Exception {
                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailbox WHERE id=:id;")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                MailboxId inboxNewId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxNewId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }

            @Disabled("JAMES-3056 cannot create because mailbox already exists")
            @Test
            void createMailboxShouldCreateWhenMailboxPathDaoFailsOnDeleteByPath(CassandraCluster cassandra) throws Exception {
                testee.createMailbox(inboxPath, mailboxSession);

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailboxPathV2")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                MailboxId inboxNewId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxNewId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }

            @Disabled("JAMES-3056 cannot create because mailbox already exists")
            @Test
            void createMailboxShouldCreateWhenMailboxPathDaoFailsOnDeleteById(CassandraCluster cassandra) throws Exception {
                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailboxPathV2")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                MailboxId inboxNewId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxNewId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }
        }

        @Nested
        class DeleteTwice {

            @Disabled("JAMES-3056 list() returns one element with inboxPath")
            @Test
            void deleteMailboxByPathShouldDeleteWhenMailboxDaoFails(CassandraCluster cassandra) throws Exception {
                testee.createMailbox(inboxPath, mailboxSession);

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailbox WHERE id=:id;")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .isEmpty();
                    softly.assertThat(testee.list(mailboxSession))
                        .isEmpty();
                }));
            }

            @Test
            void deleteMailboxByIdShouldDeleteWhenMailboxDaoFails(CassandraCluster cassandra) throws Exception {
                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailbox WHERE id=:id;")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .isEmpty();
                    softly.assertThat(testee.list(mailboxSession))
                        .isEmpty();
                }));
            }

            @Test
            void deleteMailboxByPathShouldDeleteWhenMailboxPathDaoFails(CassandraCluster cassandra) throws Exception {
                testee.createMailbox(inboxPath, mailboxSession);

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailboxPathV2")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .isEmpty();
                    softly.assertThat(testee.list(mailboxSession))
                        .isEmpty();
                }));
            }

            @Test
            void deleteMailboxByIdShouldDeleteWhenMailboxPathDaoFails(CassandraCluster cassandra) throws Exception {
                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailboxPathV2")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .isEmpty();
                    softly.assertThat(testee.list(mailboxSession))
                        .isEmpty();
                }));
            }
        }

        @Nested
        class DeleteTwiceThenCreate {

            @Test
            void createMailboxShouldCreateWhenMailboxDaoFailsOnDeleteByPath(CassandraCluster cassandra) throws Exception {
                testee.createMailbox(inboxPath, mailboxSession);

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailbox WHERE id=:id;")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));
                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }

            @Test
            void createMailboxShouldCreateWhenMailboxDaoFailsOnDeleteById(CassandraCluster cassandra) throws Exception {
                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailbox WHERE id=:id;")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));
                MailboxId inboxNewId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxNewId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }

            @Test
            void createMailboxShouldCreateWhenMailboxPathDaoFailsOnDeleteByPath(CassandraCluster cassandra) throws Exception {
                testee.createMailbox(inboxPath, mailboxSession);

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailboxPathV2")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxPath, mailboxSession));
                MailboxId inboxNewId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxNewId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }

            @Test
            void createMailboxShouldCreateWhenMailboxPathDaoFailsOnDeleteById(CassandraCluster cassandra) throws Exception {
                MailboxId inboxId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                cassandra.getConf()
                    .fail()
                    .whenBoundStatementStartsWith("DELETE FROM mailboxPathV2")
                    .times(6)
                    .setExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));

                cassandra.getConf().resetExecutionHook();

                doQuietly(() -> testee.deleteMailbox(inboxId, mailboxSession));
                MailboxId inboxNewId = testee.createMailbox(inboxPath, mailboxSession)
                    .get();

                SoftAssertions.assertSoftly(Throwing.consumer(softly -> {
                    softly.assertThat(testee.search(allMailboxesSearchQuery, mailboxSession))
                        .hasOnlyOneElementSatisfying(mailboxMetaData -> {
                            softly.assertThat(mailboxMetaData.getId()).isEqualTo(inboxNewId);
                            softly.assertThat(mailboxMetaData.getPath()).isEqualTo(inboxPath);
                        });
                    softly.assertThat(testee.list(mailboxSession))
                        .containsExactly(inboxPath);
                }));
            }
        }
    }

    private void doQuietly(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable th) {
            // ignore
        }
    }
}
