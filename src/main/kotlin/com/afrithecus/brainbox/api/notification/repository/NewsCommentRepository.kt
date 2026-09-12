package com.afrithecus.brainbox.api.notification.repository

import com.afrithecus.brainbox.api.notification.entity.NewsCommentEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NewsCommentRepository : JpaRepository<NewsCommentEntity, UUID> {

    fun findAllByNewsIdOrderByCreatedAtDesc(newsId: UUID): List<NewsCommentEntity>
}
