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

package org.apache.james.mailbox.cassandra.mail;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import javax.mail.Flags;
import javax.mail.Flags.Flag;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.mailbox.FlagsBuilder;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.cassandra.CassandraId;
import org.apache.james.mailbox.cassandra.CassandraMessageId;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.ComposedMessageIdWithMetaData;
import org.apache.james.mailbox.model.MailboxCounters;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.apache.james.mailbox.store.FlagsUpdateCalculator;
import org.apache.james.mailbox.store.SimpleMessageMetaData;
import org.apache.james.mailbox.store.mail.MessageMapper;
import org.apache.james.mailbox.store.mail.model.Mailbox;
import org.apache.james.mailbox.store.mail.model.MailboxMessage;
import org.apache.james.mailbox.store.mail.model.impl.SimpleMailboxMessage;
import org.apache.james.util.CompletableFutureUtil;
import org.apache.james.util.FluentFutureStream;
import org.apache.james.util.OptionalConverter;
import org.apache.james.util.streams.JamesCollectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.steveash.guavate.Guavate;
import com.google.common.collect.ImmutableList;

public class CassandraMessageMapper implements MessageMapper {
    public static final MailboxCounters INITIAL_COUNTERS =  MailboxCounters.builder()
        .count(0L)
        .unseen(0L)
        .build();
    public static final int EXPUNGE_BATCH_SIZE = 100;
    public static final Logger LOGGER = LoggerFactory.getLogger(CassandraMessageMapper.class);

    private final CassandraModSeqProvider modSeqProvider;
    private final MailboxSession mailboxSession;
    private final CassandraUidProvider uidProvider;
    private final int maxRetries;
    private final CassandraMessageDAO messageDAO;
    private final CassandraMessageIdDAO messageIdDAO;
    private final CassandraMessageIdToImapUidDAO imapUidDAO;
    private final CassandraMailboxCounterDAO mailboxCounterDAO;
    private final CassandraMailboxRecentsDAO mailboxRecentDAO;
    private final CassandraApplicableFlagDAO applicableFlagDAO;
    private final CassandraIndexTableHandler indexTableHandler;
    private final CassandraFirstUnseenDAO firstUnseenDAO;
    private final AttachmentLoader attachmentLoader;
    private final CassandraDeletedMessageDAO deletedMessageDAO;

    public CassandraMessageMapper(CassandraUidProvider uidProvider, CassandraModSeqProvider modSeqProvider,
                                  MailboxSession mailboxSession, int maxRetries, CassandraAttachmentMapper attachmentMapper,
                                  CassandraMessageDAO messageDAO, CassandraMessageIdDAO messageIdDAO, CassandraMessageIdToImapUidDAO imapUidDAO,
                                  CassandraMailboxCounterDAO mailboxCounterDAO, CassandraMailboxRecentsDAO mailboxRecentDAO, CassandraApplicableFlagDAO applicableFlagDAO,
                                  CassandraIndexTableHandler indexTableHandler, CassandraFirstUnseenDAO firstUnseenDAO, CassandraDeletedMessageDAO deletedMessageDAO) {
        this.uidProvider = uidProvider;
        this.modSeqProvider = modSeqProvider;
        this.mailboxSession = mailboxSession;
        this.maxRetries = maxRetries;
        this.messageDAO = messageDAO;
        this.messageIdDAO = messageIdDAO;
        this.imapUidDAO = imapUidDAO;
        this.mailboxCounterDAO = mailboxCounterDAO;
        this.mailboxRecentDAO = mailboxRecentDAO;
        this.indexTableHandler = indexTableHandler;
        this.firstUnseenDAO = firstUnseenDAO;
        this.attachmentLoader = new AttachmentLoader(attachmentMapper);
        this.applicableFlagDAO = applicableFlagDAO;
        this.deletedMessageDAO = deletedMessageDAO;
    }

    @Override
    public long countMessagesInMailbox(Mailbox mailbox) throws MailboxException {
        return mailboxCounterDAO.countMessagesInMailbox(mailbox)
            .join()
            .orElse(0L);
    }

    @Override
    public long countUnseenMessagesInMailbox(Mailbox mailbox) throws MailboxException {
        return mailboxCounterDAO.countUnseenMessagesInMailbox(mailbox)
            .join()
            .orElse(0L);
    }

    @Override
    public MailboxCounters getMailboxCounters(Mailbox mailbox) throws MailboxException {
        return mailboxCounterDAO.retrieveMailboxCounters(mailbox)
            .join()
            .orElse(INITIAL_COUNTERS);
    }

    @Override
    public void delete(Mailbox mailbox, MailboxMessage message) {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        deleteAsFuture(message, mailboxId)
            .join();
    }

    private CompletableFuture<Void> deleteAsFuture(MailboxMessage message, CassandraId mailboxId) {
        return messageIdDAO.retrieve(mailboxId, message.getUid())
            .thenCompose(optional -> optional
                .map(this::deleteUsingMailboxId)
                .orElse(CompletableFuture.completedFuture(null)));
    }

    private CompletableFuture<Void> deleteUsingMailboxId(ComposedMessageIdWithMetaData composedMessageIdWithMetaData) {
        ComposedMessageId composedMessageId = composedMessageIdWithMetaData.getComposedMessageId();
        CassandraMessageId messageId = (CassandraMessageId) composedMessageId.getMessageId();
        CassandraId mailboxId = (CassandraId) composedMessageId.getMailboxId();
        MessageUid uid = composedMessageId.getUid();
        return CompletableFuture.allOf(
            imapUidDAO.delete(messageId, mailboxId),
            messageIdDAO.delete(mailboxId, uid)
        ).thenCompose(voidValue -> indexTableHandler.updateIndexOnDelete(composedMessageIdWithMetaData, mailboxId));
    }

    private CompletableFuture<Optional<ComposedMessageIdWithMetaData>> retrieveMessageId(CassandraId mailboxId, MailboxMessage message) {
        return messageIdDAO.retrieve(mailboxId, message.getUid());
    }

    @Override
    public Iterator<MailboxMessage> findInMailbox(Mailbox mailbox, MessageRange messageRange, FetchType ftype, int max) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return retrieveMessages(retrieveMessageIds(mailboxId, messageRange), ftype, Optional.of(max))
                .join()
                .map(SimpleMailboxMessage -> (MailboxMessage) SimpleMailboxMessage)
                .sorted(Comparator.comparing(MailboxMessage::getUid))
                .iterator();
    }

    private List<ComposedMessageIdWithMetaData> retrieveMessageIds(CassandraId mailboxId, MessageRange messageRange) {
        return messageIdDAO.retrieveMessages(mailboxId, messageRange)
                .join()
                .collect(Guavate.toImmutableList());
    }

    private CompletableFuture<Stream<SimpleMailboxMessage>> retrieveMessages(List<ComposedMessageIdWithMetaData> messageIds, FetchType fetchType, Optional<Integer> limit) {
        CompletableFuture<Stream<Pair<CassandraMessageDAO.MessageWithoutAttachment, Stream<CassandraMessageDAO.MessageAttachmentRepresentation>>>>
            messageRepresentations = messageDAO.retrieveMessages(messageIds, fetchType, limit);
        if (fetchType == FetchType.Body || fetchType == FetchType.Full) {
            return FluentFutureStream.of(messageRepresentations)
                .thenComposeOnAll(pair ->
                    attachmentLoader.getAttachments(pair.getRight().collect(Guavate.toImmutableList()))
                        .thenApply(attachments -> Pair.of(pair.getLeft(), attachments))
                )
                .map(pair ->
                    pair.getLeft()
                        .toMailboxMessage(pair.getRight()
                            .stream()
                            .collect(Guavate.toImmutableList())))
                .completableFuture();
        } else {
            return FluentFutureStream.of(messageRepresentations)
                .map(pair ->
                    pair
                        .getLeft()
                        .toMailboxMessage(ImmutableList.of()))
                .completableFuture();
        }
    }

    @Override
    public List<MessageUid> findRecentMessageUidsInMailbox(Mailbox mailbox) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return mailboxRecentDAO.getRecentMessageUidsInMailbox(mailboxId)
                .join();
    }

    @Override
    public MessageUid findFirstUnseenMessageUid(Mailbox mailbox) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return firstUnseenDAO.retrieveFirstUnread(mailboxId)
            .join()
            .orElse(null);
    }

    @Override
    public Map<MessageUid, MessageMetaData> expungeMarkedForDeletionInMailbox(Mailbox mailbox, MessageRange messageRange) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        return FluentFutureStream.of(deletedMessageDAO.retrieveDeletedMessage(mailboxId, messageRange))
            .completableFuture()
            .thenApply(JamesCollectors.chunk(EXPUNGE_BATCH_SIZE))
            .thenCompose(chunkedExpungedUids ->
                CompletableFutureUtil.chainAll(chunkedExpungedUids,
                    uidChunk ->  expungeUidChunk(mailboxId, uidChunk)))
            .thenApply(s -> s.flatMap(i -> i))
            .join()
            .collect(Guavate.toImmutableMap(MailboxMessage::getUid, SimpleMessageMetaData::new));
    }

    private CompletableFuture<Stream<SimpleMailboxMessage>> expungeUidChunk(CassandraId mailboxId, List<MessageUid> uidChunk) {
        return FluentFutureStream.of(uidChunk.stream()
            .map(uid -> messageIdDAO.retrieve(mailboxId, uid)))
            .flatMap(OptionalConverter::toStream)
            .performOnAll(this::deleteUsingMailboxId)
            .thenComposeOnAll(idWithMetadata -> messageDAO.retrieveMessages(ImmutableList.of(idWithMetadata), FetchType.Metadata, Optional.empty()))
            .flatMap(s -> s)
            .map(pair -> pair.getKey().toMailboxMessage(ImmutableList.of()))
            .completableFuture();
    }

    @Override
    public MessageMetaData move(Mailbox destinationMailbox, MailboxMessage original) throws MailboxException {
        CassandraId originalMailboxId = (CassandraId) original.getMailboxId();
        MessageMetaData messageMetaData = copy(destinationMailbox, original);
        retrieveMessageId(originalMailboxId, original)
            .thenCompose(optional -> optional.map(this::deleteUsingMailboxId).orElse(CompletableFuture.completedFuture(null)))
            .join();
        return messageMetaData;
    }

    @Override
    public void endRequest() {
        // Do nothing
    }

    @Override
    public long getHighestModSeq(Mailbox mailbox) throws MailboxException {
        return modSeqProvider.highestModSeq(mailboxSession, mailbox);
    }

    @Override
    public MessageMetaData add(Mailbox mailbox, MailboxMessage message) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        CompletableFuture<Optional<MessageUid>> uidFuture = uidProvider.nextUid(mailboxId);
        CompletableFuture<Optional<Long>> modseqFuture = modSeqProvider.nextModSeq(mailboxId);
        CompletableFuture.allOf(uidFuture, modseqFuture).join();

        message.setUid(uidFuture.join()
            .orElseThrow(() -> new MailboxException("Can not find a UID to save " + message.getMessageId() + " in " + mailboxId)));
        message.setModSeq(modseqFuture.join()
            .orElseThrow(() -> new MailboxException("Can not find a MODSEQ to save " + message.getMessageId() + " in " + mailboxId)));

        save(mailbox, message)
            .thenCompose(voidValue -> indexTableHandler.updateIndexOnAdd(message, mailboxId))
            .join();
        return new SimpleMessageMetaData(message);
    }

    @Override
    public Iterator<UpdatedFlags> updateFlags(Mailbox mailbox, FlagsUpdateCalculator flagUpdateCalculator, MessageRange set) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();

        return runUpdate(mailboxId, set, flagUpdateCalculator).iterator();
    }

    private List<UpdatedFlags> runUpdate(CassandraId mailboxId, MessageRange set, FlagsUpdateCalculator flagsUpdateCalculator) throws MailboxException {
        Stream<ComposedMessageIdWithMetaData> toBeUpdated = messageIdDAO.retrieveMessages(mailboxId, set).join();

        FlagsUpdateStageResult globalResult = runUpdateStage(mailboxId, toBeUpdated, flagsUpdateCalculator);

        int retryCount = 0;

        while (retryCount < maxRetries && !globalResult.getFailed().isEmpty()) {
            retryCount++;
            FlagsUpdateStageResult stageResult = runUpdateStage(mailboxId,
                FluentFutureStream.of(
                    globalResult.getFailed().stream()
                        .map(uid -> messageIdDAO.retrieve(mailboxId, uid)))
                    .flatMap(OptionalConverter::toStream)
                    .completableFuture().join(),
                flagsUpdateCalculator);

            globalResult = globalResult.keepSuccess().merge(stageResult);
        }

        LOGGER.error("Can not update following UIDs {} for mailbox {}", globalResult.getFailed(), mailboxId.asUuid());

        return globalResult.getSucceeded();
    }

    private FlagsUpdateStageResult runUpdateStage(CassandraId mailboxId, Stream<ComposedMessageIdWithMetaData> toBeUpdated, FlagsUpdateCalculator flagsUpdateCalculator) {
        Long newModSeq = modSeqProvider.nextModSeq(mailboxId).join().orElseThrow(() -> new RuntimeException("ModSeq generation failed"));

        FlagsUpdateStageResult result = toBeUpdated
            .map(oldMetadata -> tryFlagsUpdate(flagsUpdateCalculator,
                newModSeq,
                oldMetadata))
            .reduce(FlagsUpdateStageResult::merge)
            .orElse(none());

        result.getSucceeded().stream()
            .map((UpdatedFlags updatedFlags) -> indexTableHandler.updateIndexOnFlagsUpdate(mailboxId, updatedFlags)
                .thenApply(voidValue -> updatedFlags))
            .forEach(CompletableFuture::join);

        return result;
    }

    @Override
    public <T> T execute(Transaction<T> transaction) throws MailboxException {
        return transaction.run();
    }

    @Override
    public MessageMetaData copy(Mailbox mailbox, MailboxMessage original) throws MailboxException {
        original.setFlags(new FlagsBuilder().add(original.createFlags()).add(Flag.RECENT).build());
        return add(mailbox, original);
    }

    @Override
    public com.google.common.base.Optional<MessageUid> getLastUid(Mailbox mailbox) throws MailboxException {
        return uidProvider.lastUid(mailboxSession, mailbox);
    }

    @Override
    public Flags getApplicableFlag(Mailbox mailbox) throws MailboxException {
        return applicableFlagDAO.retrieveApplicableFlag((CassandraId) mailbox.getMailboxId())
            .join()
            .orElse(new Flags());
    }

    private CompletableFuture<Void> save(Mailbox mailbox, MailboxMessage message) throws MailboxException {
        CassandraId mailboxId = (CassandraId) mailbox.getMailboxId();
        return messageDAO.save(message)
            .thenCompose(aVoid -> insertIds(message, mailboxId));
    }

    private CompletableFuture<Void> insertIds(MailboxMessage message, CassandraId mailboxId) {
        ComposedMessageIdWithMetaData composedMessageIdWithMetaData = ComposedMessageIdWithMetaData.builder()
                .composedMessageId(new ComposedMessageId(mailboxId, message.getMessageId(), message.getUid()))
                .flags(message.createFlags())
                .modSeq(message.getModSeq())
                .build();
        return CompletableFuture.allOf(messageIdDAO.insert(composedMessageIdWithMetaData),
                imapUidDAO.insert(composedMessageIdWithMetaData));
    }


    private FlagsUpdateStageResult tryFlagsUpdate(FlagsUpdateCalculator flagUpdateCalculator, long newModSeq, ComposedMessageIdWithMetaData oldMetaData) {
        Flags oldFlags = oldMetaData.getFlags();
        Flags newFlags = flagUpdateCalculator.buildNewFlags(oldFlags);

        if (identicalFlags(oldFlags, newFlags)) {
            return success(UpdatedFlags.builder()
                .uid(oldMetaData.getComposedMessageId().getUid())
                .modSeq(oldMetaData.getModSeq())
                .oldFlags(oldFlags)
                .newFlags(newFlags)
                .build());
        }

        if (updateFlags(oldMetaData, newFlags, newModSeq)) {
            return success(UpdatedFlags.builder()
                .uid(oldMetaData.getComposedMessageId().getUid())
                .modSeq(newModSeq)
                .oldFlags(oldFlags)
                .newFlags(newFlags)
                .build());
        } else {
            return fail(oldMetaData.getComposedMessageId().getUid());
        }
    }

    private boolean identicalFlags(Flags oldFlags, Flags newFlags) {
        return oldFlags.equals(newFlags);
    }

    private boolean updateFlags(ComposedMessageIdWithMetaData oldMetadata, Flags newFlags, long newModSeq) {
        ComposedMessageIdWithMetaData composedMessageIdWithMetaData = ComposedMessageIdWithMetaData.builder()
                .composedMessageId(oldMetadata.getComposedMessageId())
                .modSeq(newModSeq)
                .flags(newFlags)
                .build();
        return imapUidDAO.updateMetadata(composedMessageIdWithMetaData, oldMetadata.getModSeq())
            .thenCompose(success -> Optional.of(success)
                .filter(b -> b)
                .map((Boolean any) -> messageIdDAO.updateMetadata(composedMessageIdWithMetaData)
                    .thenApply(v -> success))
                .orElse(CompletableFuture.completedFuture(success)))
            .join();
    }

    private static FlagsUpdateStageResult success(UpdatedFlags updatedFlags) {
        return new FlagsUpdateStageResult(ImmutableList.of(), ImmutableList.of(updatedFlags));
    }

    private static FlagsUpdateStageResult fail(MessageUid uid) {
        return new FlagsUpdateStageResult(ImmutableList.of(uid), ImmutableList.of());
    }

    private static FlagsUpdateStageResult none() {
        return new FlagsUpdateStageResult(ImmutableList.of(), ImmutableList.of());
    }

    private static class FlagsUpdateStageResult {
        private final List<MessageUid> failed;
        private final List<UpdatedFlags> succeeded;

        public FlagsUpdateStageResult(List<MessageUid> failed, List<UpdatedFlags> succeeded) {
            this.failed = failed;
            this.succeeded = succeeded;
        }

        public List<MessageUid> getFailed() {
            return failed;
        }

        public List<UpdatedFlags> getSucceeded() {
            return succeeded;
        }

        public FlagsUpdateStageResult merge(FlagsUpdateStageResult other) {
            return new FlagsUpdateStageResult(
                ImmutableList.<MessageUid>builder()
                    .addAll(this.failed)
                    .addAll(other.failed)
                    .build(),
                ImmutableList.<UpdatedFlags>builder()
                    .addAll(this.succeeded)
                    .addAll(other.succeeded)
                    .build());
        }

        public FlagsUpdateStageResult keepSuccess() {
            return new FlagsUpdateStageResult(ImmutableList.of(), succeeded);
        }
    }
}
