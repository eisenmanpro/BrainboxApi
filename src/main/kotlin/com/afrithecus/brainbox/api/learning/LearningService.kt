package com.afrithecus.brainbox.api.learning

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.common.domain.GradeNormalizer
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.entity.LearningContentEntity
import com.afrithecus.brainbox.api.learning.entity.LearningPostEntity
import com.afrithecus.brainbox.api.learning.entity.LearningViewEntity
import com.afrithecus.brainbox.api.learning.model.ContentType
import com.afrithecus.brainbox.api.learning.model.LearningScope
import com.afrithecus.brainbox.api.learning.repository.LearningContentRepository
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import com.afrithecus.brainbox.api.learning.repository.LearningViewRepository
import com.afrithecus.brainbox.api.learning.web.CreateContentRequest
import com.afrithecus.brainbox.api.learning.web.CreatePostRequest
import com.afrithecus.brainbox.api.learning.web.LearningContentPayload
import com.afrithecus.brainbox.api.learning.web.LearningPostPayload
import com.afrithecus.brainbox.api.learning.web.QuizQuestionRequest
import com.afrithecus.brainbox.api.exams.QuestionCodec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Learning hub: scope-filtered discovery, key-sanitized content delivery and
 * deduplicated view tracking (doc 03 §1-§2).
 */
@Service
class LearningService(
    private val postRepository: LearningPostRepository,
    private val contentRepository: LearningContentRepository,
    private val viewRepository: LearningViewRepository,
    private val codec: QuestionCodec,
    private val mapper: ObjectMapper,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun featured(user: UserEntity): List<LearningPostPayload> =
        visiblePosts(user).filter { it.isFeatured }.sortedByDescending { it.createdAt }.map { toPost(it) }

    @Transactional(readOnly = true)
    fun trending(user: UserEntity): List<LearningPostPayload> =
        visiblePosts(user).sortedByDescending { it.viewCount }.take(TRENDING_LIMIT).map { toPost(it, trending = true) }

    @Transactional(readOnly = true)
    fun bySubject(user: UserEntity, subject: String): List<LearningPostPayload> =
        visiblePosts(user).filter { it.subject.equals(subject.trim(), ignoreCase = true) }
            .sortedByDescending { it.createdAt }
            .map { toPost(it) }

    @Transactional(readOnly = true)
    fun search(user: UserEntity, query: String): List<LearningPostPayload> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return visiblePosts(user)
            .filter { post ->
                val topic = post.topic
                val subtopic = post.subtopic
                post.title.contains(q, ignoreCase = true) ||
                    (topic != null && topic.contains(q, ignoreCase = true)) ||
                    (subtopic != null && subtopic.contains(q, ignoreCase = true)) ||
                    post.subject.contains(q, ignoreCase = true)
            }
            .sortedByDescending { it.viewCount }
            .map { toPost(it) }
    }

    @Transactional(readOnly = true)
    fun detail(user: UserEntity, postIdRaw: String): LearningPostPayload {
        val post = findVisible(user, postIdRaw)
        val blocks = contentRepository.findAllByPostIdOrderByOrderIndexAsc(post.id)
        return toPost(post).copy(content = blocks.map { toContent(it, includeKeys = false) })
    }

    @Transactional(readOnly = true)
    fun contentOf(user: UserEntity, postIdRaw: String): List<LearningContentPayload> {
        val post = findVisible(user, postIdRaw)
        return contentRepository.findAllByPostIdOrderByOrderIndexAsc(post.id)
            .map { toContent(it, includeKeys = false) }
    }

    @Transactional
    fun recordView(user: UserEntity, postIdRaw: String) {
        val post = findVisible(user, postIdRaw)
        val dayKey = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(clock.instant())
        val existing = viewRepository.findByUserIdAndPostIdAndDayKey(user.id, post.id, dayKey)
        if (existing == null) {
            viewRepository.save(
                LearningViewEntity().apply {
                    this.postId = post.id
                    this.userId = user.id
                    this.dayKey = dayKey
                }
            )
            post.viewCount = post.viewCount + 1
            postRepository.save(post)
        }
    }

    // ------------------------------------------------------------ internals

    private fun visiblePosts(user: UserEntity): List<LearningPostEntity> =
        postRepository.findAllByIsPublishedTrue().filter { isVisible(it, user) }

    private fun findVisible(user: UserEntity, raw: String): LearningPostEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument("post id is not a valid identifier")
        val post = postRepository.findById(id).orElse(null) ?: throw notFound("Post not found")
        if (!post.isPublished || !isVisible(post, user)) throw notFound("Post not found")
        return post
    }

    /** Content scope rules (doc 03 §1.2). */
    private fun isVisible(post: LearningPostEntity, user: UserEntity): Boolean =
        ContentScope.isVisible(post.scope, post.schoolId, post.gradeLevel, post.teacherId, user)

    private fun toPost(post: LearningPostEntity, trending: Boolean = false): LearningPostPayload {
        val canonical = CanonicalSubject.canonical(post.subject)
        val custom = post.customSubjectName?.takeIf { it.isNotBlank() }
            ?: post.subject.takeIf { canonical == null }?.trim()?.takeIf { it.isNotEmpty() }
        return LearningPostPayload(
            id = post.id.toString(),
            title = post.title,
            subject = canonical ?: CanonicalSubject.DEFAULT,
            topic = post.topic,
            subtopic = post.subtopic,
            imageUrl = post.imageUrl,
            description = post.description,
            estimatedMinutes = post.estimatedMinutes,
            difficulty = post.difficulty,
            tags = codec.parseList(post.tags),
            scope = post.scope.name,
            schoolId = post.schoolId?.toString(),
            gradeLevel = post.gradeLevel,
            teacherId = post.teacherId?.toString(),
            isFeatured = post.isFeatured,
            isPublished = post.isPublished,
            viewCount = post.viewCount,
            likeCount = post.likeCount,
            createdAt = post.createdAt.toEpochMilli(),
            isTrending = trending,
            cbcStrand = post.cbcStrand,
            cbcSubStrand = post.cbcSubStrand,
            authorName = userRepository.findById(post.createdBy).orElse(null)?.name.orEmpty(),
            customSubjectName = custom,
            status = post.status,
        )
    }

    /** QUIZ metadata is deep-cleaned of key material for students (doc 03 §2.2). */
    private fun toContent(block: LearningContentEntity, includeKeys: Boolean): LearningContentPayload {
        // The client model is `metadata: String?`, so the JSON tree is re-serialised to text.
        val metadata: String? = block.metadata?.let { raw ->
            if (block.contentType == ContentType.QUIZ && !includeKeys) {
                runCatching { mapper.writeValueAsString(stripKeys(mapper.readTree(raw))) }.getOrDefault(raw)
            } else {
                raw
            }
        }
        return LearningContentPayload(
            id = block.id.toString(),
            postId = block.postId.toString(),
            type = contentTypeForClient(block),
            title = block.title,
            content = block.content,
            durationMinutes = block.durationMinutes,
            orderIndex = block.orderIndex,
            thumbnailUrl = block.thumbnailUrl,
            metadata = metadata,
        )
    }

    /**
     * Maps the stored content type onto the client enum (NOTES, VIDEO, QUIZ, FLASHCARDS, PDF,
     * EPUB, PLAINTEXT). DIAGRAM/DOCUMENT are not client values and must never be sent raw.
     */
    private fun contentTypeForClient(block: LearningContentEntity): String {
        block.contentTypeLabel?.takeIf { it.isNotBlank() }?.let { return it }
        return when (block.contentType) {
            ContentType.DIAGRAM -> "NOTES"
            ContentType.DOCUMENT -> "PDF"
            else -> block.contentType.name
        }
    }

    private fun stripKeys(node: JsonNode): JsonNode {
        if (node.isObject) {
            val copy = (node as? tools.jackson.databind.node.ObjectNode)?.deepCopy()
                ?: return node
            // "correct" is the client-side option index the projection emits alongside
            // "correctAnswer"; both are key material for a hub quiz.
            copy.remove(listOf("correctAnswer", "correct", "explanation", "matchingPairs"))
            val names = copy.propertyNames().toList()
            for (name in names) {
                copy.get(name)?.let { child -> copy.replace(name, stripKeys(child)) }
            }
            return copy
        }
        if (node.isArray) {
            val arr = (node as? tools.jackson.databind.node.ArrayNode)?.deepCopy()
                ?: return node
            for (i in 0 until arr.size()) {
                arr.set(i, stripKeys(arr.get(i)))
            }
            return arr
        }
        return node
    }

    // ------------------------------------------------------------ authoring

    @Transactional
    fun createPost(authorUserId: UUID, request: CreatePostRequest): LearningPostPayload {
        val scope = runCatching { LearningScope.valueOf(request.scope.trim().uppercase()) }
            .getOrNull() ?: throw invalidArgument("scope must be GLOBAL, SCHOOL or SCHOOL_GRADE_CLASS")
        if (scope != LearningScope.GLOBAL && request.schoolId == null) {
            throw invalidArgument("scope " + scope.name + " requires a schoolId")
        }
        val post = LearningPostEntity().apply {
            title = request.title.trim()
            subject = request.subject.trim()
            topic = request.topic?.trim()?.takeIf { it.isNotEmpty() }
            subtopic = request.subtopic?.trim()?.takeIf { it.isNotEmpty() }
            imageUrl = request.imageUrl?.trim()?.takeIf { it.isNotEmpty() }
            description = request.description
            estimatedMinutes = request.estimatedMinutes
            difficulty = request.difficulty
            tags = codec.toJson(request.tags)
            this.scope = scope
            schoolId = request.schoolId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            gradeLevel = request.gradeLevel?.trim()?.takeIf { it.isNotEmpty() }
            teacherId = request.teacherId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            isFeatured = request.isFeatured
            isPublished = request.isPublished
            createdBy = authorUserId
        }
        postRepository.save(post)
        request.content.forEachIndexed { index, block ->
            contentRepository.save(toEntity(post.id, block, index))
        }
        return detailOf(authorUserId, post.id.toString(), includeKeys = true)
    }

    @Transactional(readOnly = true)
    fun adminGet(postIdRaw: String): LearningPostPayload = detailOfAdmin(postIdRaw)

    @Transactional
    fun unpublish(postIdRaw: String) {
        val post = findById(postIdRaw) ?: throw notFound("Post not found")
        post.isPublished = false
        postRepository.save(post)
    }

    internal fun detailOf(authorId: UUID, raw: String, includeKeys: Boolean): LearningPostPayload {
        val post = findById(raw) ?: throw notFound("Post not found")
        val blocks = contentRepository.findAllByPostIdOrderByOrderIndexAsc(post.id)
        return toPost(post).copy(content = blocks.map { toContent(it, includeKeys) })
    }

    private fun detailOfAdmin(raw: String): LearningPostPayload {
        val post = findById(raw) ?: throw notFound("Post not found")
        val blocks = contentRepository.findAllByPostIdOrderByOrderIndexAsc(post.id)
        return toPost(post).copy(content = blocks.map { toContent(it, includeKeys = true) })
    }

    private fun toEntity(postId: UUID, block: CreateContentRequest, index: Int): LearningContentEntity {
        val type = runCatching { ContentType.valueOf(block.type.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("content type must be NOTES, VIDEO, QUIZ, DIAGRAM or DOCUMENT")
        val metadataJson = if (type == ContentType.QUIZ) {
            block.quizQuestions?.let(::quizMetadataJson)
        } else {
            null
        }
        return LearningContentEntity().apply {
            this.postId = postId
            contentType = type
            title = block.title
            content = block.content
            durationMinutes = block.durationMinutes
            orderIndex = block.orderIndex ?: index
            thumbnailUrl = block.thumbnailUrl
            metadata = metadataJson
        }
    }

    private fun quizMetadataJson(questions: List<QuizQuestionRequest>): String {
        val payload = questions.map { q ->
            val qType = runCatching {
                com.afrithecus.brainbox.api.exams.model.QuestionType.valueOf(q.type.trim().uppercase())
            }.getOrNull() ?: throw invalidArgument("quiz question type has an invalid value")
            mapOf(
                "text" to q.text,
                "type" to qType.name,
                "options" to (q.options ?: emptyList<String>()),
                "correctAnswer" to q.correctAnswer,
                "explanation" to q.explanation,
                "points" to q.points,
                "matchingPairs" to (q.matchingPairs ?: emptyMap<String, String>()),
            )
        }
        return mapper.writeValueAsString(mapOf("questions" to payload))
    }

    private fun findById(raw: String): LearningPostEntity? =
        runCatching { UUID.fromString(raw) }.getOrNull()?.let { postRepository.findById(it).orElse(null) }

    private companion object {
        const val TRENDING_LIMIT = 20
    }
}
