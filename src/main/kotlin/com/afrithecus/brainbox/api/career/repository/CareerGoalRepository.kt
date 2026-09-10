package com.afrithecus.brainbox.api.career.repository

import com.afrithecus.brainbox.api.career.entity.CareerGoalEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CareerGoalRepository : JpaRepository<CareerGoalEntity, UUID> {

    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<CareerGoalEntity>

    fun findAllByUserIdAndStatusOrderByCreatedAtDesc(userId: UUID, status: String): List<CareerGoalEntity>

    fun findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId: UUID, status: String): CareerGoalEntity?
}
