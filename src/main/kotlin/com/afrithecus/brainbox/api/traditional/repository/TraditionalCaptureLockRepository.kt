package com.afrithecus.brainbox.api.traditional.repository

import com.afrithecus.brainbox.api.traditional.entity.TraditionalCaptureLockEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TraditionalCaptureLockRepository : JpaRepository<TraditionalCaptureLockEntity, UUID> {

    fun findByExamIdAndTeacherId(examId: UUID, teacherId: UUID): TraditionalCaptureLockEntity?

    fun findAllByExamId(examId: UUID): List<TraditionalCaptureLockEntity>
}
