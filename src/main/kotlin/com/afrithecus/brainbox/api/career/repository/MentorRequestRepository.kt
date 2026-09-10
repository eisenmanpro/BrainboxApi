package com.afrithecus.brainbox.api.career.repository

import com.afrithecus.brainbox.api.career.entity.MentorRequestEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MentorRequestRepository : JpaRepository<MentorRequestEntity, UUID> {

    fun findByMentorIdAndUserId(mentorId: UUID, userId: UUID): MentorRequestEntity?
}
