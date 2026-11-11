package com.afrithecus.brainbox.api.achievements.repository

import com.afrithecus.brainbox.api.achievements.entity.StreakCreditEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface StreakCreditRepository : JpaRepository<StreakCreditEntity, UUID> {

    fun findAllByUserIdOrderByCreditDateAsc(userId: UUID): List<StreakCreditEntity>

    fun findByUserIdAndCreditDate(userId: UUID, creditDate: LocalDate): StreakCreditEntity?
}
