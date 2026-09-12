package com.afrithecus.brainbox.api.learning.repository

import com.afrithecus.brainbox.api.learning.entity.ContentDraftEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContentDraftRepository : JpaRepository<ContentDraftEntity, UUID> {

    fun findByClientId(clientId: String): ContentDraftEntity?

    fun findAllByTeacherIdOrderByLastModifiedDesc(teacherId: UUID): List<ContentDraftEntity>
}
