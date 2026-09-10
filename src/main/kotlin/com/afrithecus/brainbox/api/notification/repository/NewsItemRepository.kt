package com.afrithecus.brainbox.api.notification.repository

import com.afrithecus.brainbox.api.notification.entity.NewsItemEntity
import com.afrithecus.brainbox.api.notification.model.NewsStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NewsItemRepository : JpaRepository<NewsItemEntity, UUID> {

    fun findAllByStatusOrderByPublishedAtDescCreatedAtDesc(status: NewsStatus): List<NewsItemEntity>

    fun findAllByOrderByCreatedAtDesc(): List<NewsItemEntity>

    fun findAllByStatusAndCategoryIgnoreCaseOrderByPublishedAtDesc(status: NewsStatus, category: String): List<NewsItemEntity>
}
