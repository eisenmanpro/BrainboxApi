package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ContentReviewEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContentReviewRepository : JpaRepository<ContentReviewEntity, UUID> {

    fun findByReviewerIdAndContentTypeAndContentIdAndContentVersion(
        reviewerId: UUID,
        contentType: String,
        contentId: UUID,
        contentVersion: Int,
    ): ContentReviewEntity?

    fun findAllByContentTypeAndContentIdAndContentVersion(
        contentType: String,
        contentId: UUID,
        contentVersion: Int,
    ): List<ContentReviewEntity>
}
