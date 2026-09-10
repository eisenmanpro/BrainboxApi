package com.afrithecus.brainbox.api.notification

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.notification.entity.NewsItemEntity
import com.afrithecus.brainbox.api.notification.model.NewsStatus
import com.afrithecus.brainbox.api.notification.repository.NewsItemRepository
import com.afrithecus.brainbox.api.notification.web.CreateNewsRequest
import com.afrithecus.brainbox.api.notification.web.NewsItemPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** News feed (doc 05 §6). Published articles only for readers; staff can author. */
@Service
class NewsService(
    private val repository: NewsItemRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(category: String?): List<NewsItemPayload> {
        val rows = if (category.isNullOrBlank()) {
            repository.findAllByStatusOrderByPublishedAtDescCreatedAtDesc(NewsStatus.PUBLISHED)
        } else {
            repository.findAllByStatusAndCategoryIgnoreCaseOrderByPublishedAtDesc(NewsStatus.PUBLISHED, category)
        }
        return rows.sortedByDescending { it.publishedAt ?: it.createdAt }.map(::payload)
    }

    @Transactional(readOnly = true)
    fun detail(idRaw: String): NewsItemPayload =
        payload(repository.findById(parseUuid(idRaw)).orElse(null) ?: throw notFound("News item not found"))

    @Transactional(readOnly = true)
    fun listAllForStaff(): List<NewsItemPayload> = repository.findAllByOrderByCreatedAtDesc().map(::payload)

    @Transactional
    fun create(current: CurrentUser, request: CreateNewsRequest): NewsItemPayload {
        if (current.role != Role.ADMIN) throw ApiException(ApiErrorCode.FORBIDDEN, "Only admins can publish news")
        val status = runCatching { NewsStatus.valueOf(request.status.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown news status: " + request.status)
        val publishedAt = request.publishedAt?.let(Instant::ofEpochMilli)
            ?: if (status == NewsStatus.PUBLISHED) clock.instant() else null
        val saved = repository.save(NewsItemEntity().apply {
            title = request.title.trim()
            content = request.content
            imageUrl = request.imageUrl
            category = request.category.trim().ifBlank { "General" }
            authorId = current.userId
            this.publishedAt = publishedAt
            this.status = status
            tags = mapper.writeValueAsString(request.tags)
        })
        return payload(saved)
    }

    private fun payload(row: NewsItemEntity): NewsItemPayload {
        val published = row.publishedAt ?: row.createdAt
        return NewsItemPayload(
            id = row.id.toString(),
            title = row.title,
            imageUrl = row.imageUrl ?: "",
            timestamp = published.toEpochMilli(),
            category = row.category,
            content = row.content,
            likes = row.likes,
            dislikes = row.dislikes,
            userLiked = false,
            userDisliked = false,
            commentCount = row.commentCount,
            authorId = row.authorId?.toString(),
            publishedAt = row.publishedAt?.toEpochMilli(),
            status = row.status.name,
            tags = parseList(row.tags),
        )
    }

    private fun parseList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).map { node.get(it).asString() }
    }

    private fun parseUuid(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument("news id is not a valid identifier")
}
