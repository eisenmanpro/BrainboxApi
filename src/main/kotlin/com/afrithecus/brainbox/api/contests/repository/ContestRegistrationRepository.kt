package com.afrithecus.brainbox.api.contests.repository

import com.afrithecus.brainbox.api.contests.entity.ContestRegistrationEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContestRegistrationRepository : JpaRepository<ContestRegistrationEntity, UUID> {

    fun findByStudentIdAndContestId(studentId: UUID, contestId: UUID): ContestRegistrationEntity?

    fun countByContestId(contestId: UUID): Long

    fun findAllByStudentId(studentId: UUID): List<ContestRegistrationEntity>
}
