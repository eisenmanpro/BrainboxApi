package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ModerationOutcomeEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface ModerationOutcomeRepository : JpaRepository<ModerationOutcomeEntity, UUID> {

    fun findByContentTypeAndContentIdAndContentVersion(
        contentType: String,
        contentId: UUID,
        contentVersion: Int,
    ): ModerationOutcomeEntity?

    /** O1 rollup: outcomes resolved to [state] in [from, to). */
    fun countByStateAndDecidedAtGreaterThanEqualAndDecidedAtLessThan(
        state: String,
        from: Instant,
        to: Instant,
    ): Long

    /** O1 rollup: outcomes auto-approved in [from, to). */
    fun countByAutoApprovedTrueAndDecidedAtGreaterThanEqualAndDecidedAtLessThan(
        from: Instant,
        to: Instant,
    ): Long

    /** O1 sampled audit: the newest machine approvals flagged for human spot-checking. */
    fun findByAutoApprovedTrueAndAuditSampleTrueOrderByDecidedAtDesc(
        pageable: Pageable,
    ): List<ModerationOutcomeEntity>
}
