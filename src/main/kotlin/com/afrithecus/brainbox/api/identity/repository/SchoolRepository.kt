package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SchoolRepository : JpaRepository<SchoolEntity, UUID> {
    fun findByNameIgnoreCase(name: String): SchoolEntity?

    fun findByNameContainingIgnoreCaseOrderByNameAsc(query: String): List<SchoolEntity>

    fun findAllByOrderByNameAsc(): List<SchoolEntity>

    fun findAllByIsActiveTrueOrderByNameAsc(): List<SchoolEntity>

    /**
     * Locks the school row for the rest of the transaction. The one-coordinator-per-grade rule
     * needs a serialised check-and-insert per school, the same pattern report quotas use.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SchoolEntity s WHERE s.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): SchoolEntity?
}
