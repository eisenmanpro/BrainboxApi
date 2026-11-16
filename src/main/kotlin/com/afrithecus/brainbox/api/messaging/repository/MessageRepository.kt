package com.afrithecus.brainbox.api.messaging.repository

import com.afrithecus.brainbox.api.messaging.entity.MessageEntity
import com.afrithecus.brainbox.api.messaging.model.Folder
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MessageRepository : JpaRepository<MessageEntity, UUID> {

    fun findAllByRecipientIdAndFolderOrderByCreatedAtDesc(userId: UUID, folder: Folder): List<MessageEntity>

    fun findAllBySenderIdAndFolderOrderByCreatedAtDesc(userId: UUID, folder: Folder): List<MessageEntity>

    /** The fan-out copies of one send (used for delivery receipts and thread replies). */
    fun findAllByMsgGroupAndFolder(msgGroup: String, folder: Folder): List<MessageEntity>

    /** Batch delivery-receipt lookup for a page of sent messages. */
    fun findAllByMsgGroupInAndFolder(msgGroups: Collection<String>, folder: Folder): List<MessageEntity>

    fun findByMsgGroupAndRecipientIdAndFolder(msgGroup: String, recipientId: UUID, folder: Folder): MessageEntity?

    /**
     * The caller's own copy of every message in one conversation, oldest first.
     *
     * A send stores TWO rows (the sender's SENT copy and the recipient's INBOX copy) and both
     * can name the caller — the sender is the sender of both copies — so a plain
     * "sender OR recipient" filter returns each message twice and the thread renders duplicates.
     * The thread therefore selects exactly one copy per message:
     *  - a message the caller sent: the SENT copy (folder = sent AND senderId = caller);
     *  - a message the caller received: the INBOX copy (folder = inbox AND recipientId = caller).
     */
    @org.springframework.data.jpa.repository.Query(
        """
        SELECT m FROM MessageEntity m
        WHERE (m.msgGroup = :threadId OR m.threadRootGroup = :threadId)
          AND (
            (m.folder = :sentFolder AND m.senderId = :userId)
            OR (m.folder = :inboxFolder AND m.recipientId = :userId)
          )
        ORDER BY m.createdAt ASC
        """
    )
    fun findThread(
        @org.springframework.data.repository.query.Param("threadId") threadId: String,
        @org.springframework.data.repository.query.Param("userId") userId: UUID,
        @org.springframework.data.repository.query.Param("sentFolder") sentFolder: Folder,
        @org.springframework.data.repository.query.Param("inboxFolder") inboxFolder: Folder,
    ): List<MessageEntity>

    fun findFirstByMsgGroupAndSenderIdOrderByCreatedAtAsc(msgGroup: String, senderId: UUID): MessageEntity?

    fun existsByMsgGroupAndSenderId(msgGroup: String, senderId: UUID): Boolean
}
