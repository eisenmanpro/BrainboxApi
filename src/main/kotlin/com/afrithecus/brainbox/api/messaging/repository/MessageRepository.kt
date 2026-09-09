package com.afrithecus.brainbox.api.messaging.repository

import com.afrithecus.brainbox.api.messaging.entity.MessageEntity
import com.afrithecus.brainbox.api.messaging.model.Folder
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MessageRepository : JpaRepository<MessageEntity, UUID> {

    fun findAllByRecipientIdAndFolderOrderByCreatedAtDesc(userId: UUID, folder: Folder): List<MessageEntity>

    fun findAllBySenderIdAndFolderOrderByCreatedAtDesc(userId: UUID, folder: Folder): List<MessageEntity>

    fun findFirstByMsgGroupAndSenderIdOrderByCreatedAtAsc(msgGroup: String, senderId: UUID): MessageEntity?

    fun existsByMsgGroupAndSenderId(msgGroup: String, senderId: UUID): Boolean
}
