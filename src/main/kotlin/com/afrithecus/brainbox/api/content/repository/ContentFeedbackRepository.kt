package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ContentFeedbackEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContentFeedbackRepository : JpaRepository<ContentFeedbackEntity, UUID> {

    fun findByReviewerIdAndContentTypeAndContentId(
        reviewerId: UUID,
        contentType: String,
        contentId: UUID,
    ): ContentFeedbackEntity?
}
