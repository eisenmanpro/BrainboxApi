package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ReviewerTrustEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReviewerTrustRepository : JpaRepository<ReviewerTrustEntity, UUID> {

    fun findByTeacherId(teacherId: UUID): ReviewerTrustEntity?
}
