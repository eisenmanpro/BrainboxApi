package com.afrithecus.brainbox.api.cbcratings.repository

import com.afrithecus.brainbox.api.cbcratings.entity.CbcRatingEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CbcRatingRepository : JpaRepository<CbcRatingEntity, UUID> {

    fun findByStudentIdAndStrandCodeAndTerm(
        studentId: UUID,
        strandCode: String,
        term: String,
    ): CbcRatingEntity?

    fun findAllByStudentIdAndTermOrderByStrandCodeAsc(studentId: UUID, term: String): List<CbcRatingEntity>

    fun findAllByStudentIdInAndTerm(studentIds: Collection<UUID>, term: String): List<CbcRatingEntity>

    fun findAllByStudentIdInAndStrandCode(
        studentIds: Collection<UUID>,
        strandCode: String,
    ): List<CbcRatingEntity>

    fun findAllByStudentIdOrderByRatedAtDesc(studentId: UUID): List<CbcRatingEntity>
}
