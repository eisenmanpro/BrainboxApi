package com.afrithecus.brainbox.api.classchat.repository

import com.afrithecus.brainbox.api.classchat.entity.ClassGroupMessageEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface ClassGroupMessageRepository : JpaRepository<ClassGroupMessageEntity, UUID> {

    fun findAllByGroupIdOrderByCreatedAtDesc(groupId: UUID): List<ClassGroupMessageEntity>

    fun countByGroupIdAndCreatedAtAfterAndSenderIdNot(groupId: UUID, after: Instant, senderId: UUID): Long

    fun findFirstByGroupIdOrderByCreatedAtDesc(groupId: UUID): ClassGroupMessageEntity?
}
