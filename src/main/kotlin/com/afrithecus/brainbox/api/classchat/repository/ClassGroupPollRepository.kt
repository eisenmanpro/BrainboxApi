package com.afrithecus.brainbox.api.classchat.repository

import com.afrithecus.brainbox.api.classchat.entity.ClassGroupPollEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ClassGroupPollRepository : JpaRepository<ClassGroupPollEntity, UUID> {

    fun findAllByGroupIdOrderByCreatedAtAsc(groupId: UUID): List<ClassGroupPollEntity>

    fun findByClientId(clientId: String): ClassGroupPollEntity?
}
