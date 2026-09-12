package com.afrithecus.brainbox.api.classchat.repository

import com.afrithecus.brainbox.api.classchat.entity.ClassGroupReadEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ClassGroupReadRepository : JpaRepository<ClassGroupReadEntity, UUID> {

    fun findByGroupIdAndUserId(groupId: UUID, userId: UUID): ClassGroupReadEntity?
}
