package com.afrithecus.brainbox.api.career.repository

import com.afrithecus.brainbox.api.career.entity.UserElectiveSubjectEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserElectiveSubjectRepository : JpaRepository<UserElectiveSubjectEntity, UUID> {

    fun findAllByUserId(userId: UUID): List<UserElectiveSubjectEntity>

    fun deleteAllByUserId(userId: UUID)

    fun existsByUserIdAndSubjectId(userId: UUID, subjectId: UUID): Boolean
}
