package com.afrithecus.brainbox.api.notification

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.entity.NewsCommentEntity
import com.afrithecus.brainbox.api.notification.entity.NewsItemEntity
import com.afrithecus.brainbox.api.notification.entity.NewsReportEntity
import com.afrithecus.brainbox.api.notification.entity.NewsVoteEntity
import com.afrithecus.brainbox.api.notification.model.NewsStatus
import com.afrithecus.brainbox.api.notification.repository.NewsCommentRepository
import com.afrithecus.brainbox.api.notification.repository.NewsItemRepository
import com.afrithecus.brainbox.api.notification.repository.NewsReportRepository
import com.afrithecus.brainbox.api.notification.repository.NewsVoteRepository
import com.afrithecus.brainbox.api.notification.web.AddNewsCommentRequest
import com.afrithecus.brainbox.api.notification.web.CreateNewsRequest
import com.afrithecus.brainbox.api.notification.web.NewsCommentPayload
import com.afrithecus.brainbox.api.notification.web.NewsItemPayload
import com.afrithecus.brainbox.api.notification.web.NewsReportRequest
import com.afrithecus.brainbox.api.notification.web.NewsReportResponsePayload
import com.afrithecus.brainbox.api.notification.web.NewsVoteRequest
import com.afrithecus.brainbox.api.notification.web.NewsVoteResponsePayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * News feed and engagement (docs/ongoing/api_news_changes.md). Reads are public;
 * comments, votes and reports require a session and derive the viewer identity
 * server-side.
 *
 * Article authoring is open to teaching staff and platform administrators. A platform admin may
 * manage every article; a teacher may create their own and edit or delete only what they published.
 * A teacher's article is published immediately and is attributed to their account name, which the
 * client cannot choose; the platform admin keeps the final say over the public feed.
 */
@Service
class NewsService(
    private val repository: NewsItemRepository,
    private val commentRepository: NewsCommentRepository,
    private val voteRepository: NewsVoteRepository,
    private val reportRepository: NewsReportRepository,
    private val userRepository: UserRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(current: CurrentUser?, category: String?): List<NewsItemPayload> {
        val rows = if (category.isNullOrBlank()) {
            repository.findAllByStatusOrderByPublishedAtDescCreatedAtDesc(NewsStatus.PUBLISHED)
        } else {
            repository.findAllByStatusAndCategoryIgnoreCaseOrderByPublishedAtDesc(NewsStatus.PUBLISHED, category)
        }.sortedByDescending { it.publishedAt ?: it.createdAt }
        val votes = viewerVotes(current, rows.map { it.id })
        return rows.map { payload(it, votes[it.id], current) }
    }

    @Transactional(readOnly = true)
    fun detail(current: CurrentUser?, idRaw: String): NewsItemPayload {
        val row = requireArticle(idRaw)
        val vote = current?.let { voteRepository.findByNewsIdAndUserId(row.id, it.userId)?.voteType }
        return payload(row, vote, current)
    }

    @Transactional(readOnly = true)
    fun comments(idRaw: String): List<NewsCommentPayload> =
        commentRepository.findAllByNewsIdOrderByCreatedAtDesc(requireArticle(idRaw).id).map(::commentPayload)

    @Transactional
    fun addComment(current: CurrentUser, idRaw: String, request: AddNewsCommentRequest): NewsCommentPayload {
        val article = requireArticle(idRaw)
        val content = request.content.trim()
        if (content.isEmpty()) throw invalidArgument("comment must not be blank")
        val comment = commentRepository.save(NewsCommentEntity().apply {
            newsId = article.id
            userId = current.userId
            userName = request.authorName?.trim()?.takeIf { it.isNotEmpty() } ?: "BrainBox Reader"
            userAvatar = request.authorAvatar?.trim()?.takeIf { it.isNotEmpty() }
            this.content = content
        })
        article.commentCount += 1
        repository.save(article)
        return commentPayload(comment)
    }

    @Transactional
    fun vote(current: CurrentUser, idRaw: String, request: NewsVoteRequest): NewsVoteResponsePayload {
        val article = requireArticle(idRaw)
        val desired = request.vote.trim().uppercase()
        if (desired !in setOf("UPVOTE", "DOWNVOTE", "NONE")) throw invalidArgument("Unknown vote: " + request.vote)
        val existing = voteRepository.findByNewsIdAndUserId(article.id, current.userId)
        when {
            desired == "NONE" -> if (existing != null) {
                decrement(article, existing.voteType)
                voteRepository.delete(existing)
            }
            existing == null -> {
                increment(article, desired)
                voteRepository.save(NewsVoteEntity().apply {
                    newsId = article.id
                    userId = current.userId
                    voteType = desired
                })
            }
            existing.voteType != desired -> {
                decrement(article, existing.voteType)
                increment(article, desired)
                existing.voteType = desired
                existing.createdAt = clock.instant()
                voteRepository.save(existing)
            }
        }
        repository.save(article)
        return NewsVoteResponsePayload(article.likes, article.dislikes, desired)
    }

    @Transactional
    fun report(current: CurrentUser, idRaw: String, request: NewsReportRequest): NewsReportResponsePayload {
        val article = requireArticle(idRaw)
        val reason = request.reason.trim()
        if (reason !in REPORT_REASONS) throw invalidArgument("Unknown report reason: " + request.reason)
        val saved = reportRepository.save(NewsReportEntity().apply {
            newsId = article.id
            userId = current.userId
            this.reason = reason
            details = request.details?.trim()?.takeIf { it.isNotEmpty() }
        })
        return NewsReportResponsePayload(
            reportId = saved.id.toString(),
            status = "RECEIVED",
            submittedAt = saved.createdAt.toEpochMilli(),
        )
    }

    @Transactional(readOnly = true)
    fun listAllForStaff(): List<NewsItemPayload> = repository.findAllByOrderByCreatedAtDesc().map { payload(it, null) }

    @Transactional
    fun create(current: CurrentUser, request: CreateNewsRequest): NewsItemPayload {
        val isPlatformAdmin = requireNewsAuthor(current)
        // A teacher's article is published immediately and carries the account's own name: the
        // public byline is not client-chosen, and a teacher cannot leave an article invisible in a
        // draft state they have no way to manage. A platform admin keeps the existing draft/
        // scheduled semantics.
        val status = if (isPlatformAdmin) parseStatus(request.status) else NewsStatus.PUBLISHED
        val publishedAt = if (isPlatformAdmin) {
            request.publishedAt?.let(Instant::ofEpochMilli)
                ?: if (status == NewsStatus.PUBLISHED) clock.instant() else null
        } else {
            clock.instant()
        }
        val saved = repository.save(NewsItemEntity().apply {
            title = request.title.trim()
            content = request.content
            imageUrl = request.imageUrl?.trim()?.takeIf { it.isNotEmpty() }
            category = request.category.trim().ifBlank { "News" }
            author = if (isPlatformAdmin) {
                request.author?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_AUTHOR
            } else {
                displayName(current)
            }
            authorId = current.userId
            this.publishedAt = publishedAt
            this.status = status
            tags = mapper.writeValueAsString(request.tags)
        })
        return payload(saved, null, current)
    }

    @Transactional
    fun update(current: CurrentUser, idRaw: String, request: CreateNewsRequest): NewsItemPayload {
        val isPlatformAdmin = requireNewsAuthor(current)
        val article = requireArticle(idRaw)
        requireOwnership(current, article)
        val status = if (isPlatformAdmin) parseStatus(request.status) else NewsStatus.PUBLISHED
        article.title = request.title.trim()
        article.content = request.content
        article.imageUrl = request.imageUrl?.trim()?.takeIf { it.isNotEmpty() }
        article.category = request.category.trim().ifBlank { "News" }
        if (isPlatformAdmin) {
            request.author?.trim()?.takeIf { it.isNotEmpty() }?.let { article.author = it }
            request.publishedAt?.let { article.publishedAt = Instant.ofEpochMilli(it) }
                ?: run {
                    if (status == NewsStatus.PUBLISHED && article.publishedAt == null) {
                        article.publishedAt = clock.instant()
                    }
                }
        } else if (article.publishedAt == null) {
            article.publishedAt = clock.instant()
        }
        article.status = status
        repository.save(article)
        return payload(article, null, current)
    }

    @Transactional
    fun delete(current: CurrentUser, idRaw: String) {
        requireNewsAuthor(current)
        val article = requireArticle(idRaw)
        requireOwnership(current, article)
        repository.delete(article)
    }

    // ------------------------------------------------------------ internals

    /**
     * The public payload never carries the writer's account id: it is only returned to that writer
     * (so the app can offer edit/delete on their own article) or to a platform admin. Readers need
     * no identifier to read the news.
     */
    private fun payload(
        row: NewsItemEntity,
        myVote: String?,
        current: CurrentUser? = null,
    ): NewsItemPayload {
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
            userLiked = myVote == "UPVOTE",
            userDisliked = myVote == "DOWNVOTE",
            commentCount = row.commentCount,
            author = row.author,
            authorId = if (current != null && (current.role == Role.ADMIN || current.userId == row.authorId)) {
                row.authorId?.toString()
            } else {
                null
            },
            publishedAt = row.publishedAt?.toEpochMilli(),
            status = row.status.name,
            tags = parseList(row.tags),
        )
    }

    private fun commentPayload(row: NewsCommentEntity): NewsCommentPayload =
        NewsCommentPayload(
            id = row.id.toString(),
            userName = row.userName,
            userAvatar = row.userAvatar,
            content = row.content,
            timestamp = row.createdAt.toEpochMilli(),
        )

    private fun viewerVotes(current: CurrentUser?, ids: List<UUID>): Map<UUID, String> {
        if (current == null || ids.isEmpty()) return emptyMap()
        return voteRepository.findAllByUserIdAndNewsIdIn(current.userId, ids).associate { it.newsId to it.voteType }
    }

    private fun increment(article: NewsItemEntity, type: String) {
        if (type == "UPVOTE") article.likes += 1 else article.dislikes += 1
    }

    private fun decrement(article: NewsItemEntity, type: String) {
        if (type == "UPVOTE") article.likes = (article.likes - 1).coerceAtLeast(0)
        else article.dislikes = (article.dislikes - 1).coerceAtLeast(0)
    }

    private fun requireArticle(raw: String): NewsItemEntity =
        repository.findById(parseUuid(raw)).orElse(null) ?: throw notFound("News item not found")

    /**
     * Authoring is open to teaching staff and platform administrators. Returns true when the caller
     * is a platform admin (who may also manage other people's articles), false for a teacher (own
     * articles only). Learners and guardians may read and engage, never author.
     */
    private fun requireNewsAuthor(current: CurrentUser): Boolean = when (current.role) {
        Role.ADMIN -> true
        Role.TEACHER -> false
        else -> throw ApiException(
            ApiErrorCode.FORBIDDEN,
            "Only teachers and platform administrators can publish news",
        )
    }

    /** A teacher may only change what they published; a platform admin may moderate anything. */
    private fun requireOwnership(current: CurrentUser, article: NewsItemEntity) {
        if (current.role == Role.ADMIN) return
        if (article.authorId == current.userId) return
        throw ApiException(ApiErrorCode.FORBIDDEN, "You can only change news you published")
    }

    /** The byline shown on a teacher's article: their account name, never a client-supplied string. */
    private fun displayName(current: CurrentUser): String =
        userRepository.findById(current.userId).map { it.name.trim() }.orElse("")
            .takeIf { it.isNotEmpty() } ?: DEFAULT_TEACHER_AUTHOR

    private fun parseStatus(raw: String): NewsStatus =
        runCatching { NewsStatus.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown news status: " + raw)

    private fun parseList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).map { node.get(it).asString() }
    }

    private fun parseUuid(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument("news id is not a valid identifier")

    private companion object {
        const val DEFAULT_AUTHOR = "BrainBox Editorial"
        const val DEFAULT_TEACHER_AUTHOR = "BrainBox Teacher"
        val REPORT_REASONS = setOf(
            "Spam or misleading",
            "Harassment or hate speech",
            "False information",
            "Inappropriate content",
            "Copyright violation",
            "Other",
        )
    }
}
