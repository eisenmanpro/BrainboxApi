package com.afrithecus.brainbox.api.notification.repository

import com.afrithecus.brainbox.api.notification.entity.NewsVoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NewsVoteRepository : JpaRepository<NewsVoteEntity, UUID> {

    fun findByNewsIdAndUserId(newsId: UUID, userId: UUID): NewsVoteEntity?

    fun findAllByUserIdAndNewsIdIn(userId: UUID, newsIds: Collection<UUID>): List<NewsVoteEntity>
}
