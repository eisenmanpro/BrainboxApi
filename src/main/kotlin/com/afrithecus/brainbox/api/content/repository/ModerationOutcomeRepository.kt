package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ModerationOutcomeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ModerationOutcomeRepository : JpaRepository<ModerationOutcomeEntity, UUID> {

    fun findByContentTypeAndContentIdAndContentVersion(
        contentType: String,
        contentId: UUID,
        contentVersion: Int,
    ): ModerationOutcomeEntity?
}
