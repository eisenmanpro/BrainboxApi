package com.afrithecus.brainbox.api.learning

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.entity.ContentDraftEntity
import com.afrithecus.brainbox.api.learning.entity.LearningContentEntity
import com.afrithecus.brainbox.api.learning.entity.LearningPostEntity
import com.afrithecus.brainbox.api.learning.model.ContentType
import com.afrithecus.brainbox.api.learning.model.LearningScope
import com.afrithecus.brainbox.api.learning.repository.ContentDraftRepository
import com.afrithecus.brainbox.api.learning.repository.LearningContentRepository
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import com.afrithecus.brainbox.api.learning.repository.LearningProgressRepository
import com.afrithecus.brainbox.api.learning.repository.LearningViewRepository
import com.afrithecus.brainbox.api.learning.repository.ReadableFileRepository
import com.afrithecus.brainbox.api.learning.entity.ReadableFileEntity
import com.afrithecus.brainbox.api.learning.model.FileType
import com.afrithecus.brainbox.api.learning.web.DocumentSourcePayload
import com.afrithecus.brainbox.api.learning.web.TeacherDocumentPayload
import com.afrithecus.brainbox.api.media.MediaService
import org.springframework.web.multipart.MultipartFile
import com.afrithecus.brainbox.api.learning.web.ContentAnalyticsPayload
import com.afrithecus.brainbox.api.learning.web.MaterialUpdateRequest
import com.afrithecus.brainbox.api.learning.web.StudentEngagementPayload
import com.afrithecus.brainbox.api.learning.web.TeacherContentDraftPayload
import com.afrithecus.brainbox.api.learning.web.TeacherContentPayload
import com.afrithecus.brainbox.api.learning.web.TeacherPostPayload
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Teacher content management (doc 04 section 2, docs/ongoing/api_content_changes.md):
 * the signed-in teacher's posts, material blocks, drafts and content analytics.
 * All queries are scoped to the caller; the client-side teacherId query is ignored.
 */
@Service
class TeacherContentService(
    private val postRepository: LearningPostRepository,
    private val contentRepository: LearningContentRepository,
    private val draftRepository: ContentDraftRepository,
    private val viewRepository: LearningViewRepository,
    private val progressRepository: LearningProgressRepository,
    private val fileRepository: ReadableFileRepository,
    private val mediaService: MediaService,
    private val userRepository: UserRepository,
    private val codec: QuestionCodec,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun posts(teacher: UserEntity): List<TeacherPostPayload> {
        requireTeacher(teacher)
        val rows = postRepository.findAllByCreatedByOrderByCreatedAtDesc(teacher.id)
        val names = userRepository.findAllById(rows.map { it.createdBy }.distinct()).associate { it.id to it.name }
        return rows.map { postPayload(it, names[it.createdBy] ?: "") }
    }

    @Transactional(readOnly = true)
    fun content(teacher: UserEntity, type: String?): List<TeacherContentPayload> {
        requireTeacher(teacher)
        val postIds = postRepository.findAllByCreatedByOrderByCreatedAtDesc(teacher.id).map { it.id }
        if (postIds.isEmpty()) return emptyList()
        val filter = type?.takeIf { it.isNotBlank() }?.let { materialType(it) }
        return contentRepository.findAllByPostIdIn(postIds)
            .filter { filter == null || (it.contentTypeLabel ?: it.contentType.name) == filter }
            .sortedWith(compareBy({ it.postId }, { it.orderIndex }))
            .map(::contentPayload)
    }

    /** Upserts a post by its client id (a UUID); a replayed create converges. */
    @Transactional
    fun createPost(teacher: UserEntity, payload: TeacherPostPayload): TeacherPostPayload {
        requireTeacher(teacher)
        val id = runCatching { UUID.fromString(payload.id) }.getOrNull()
        val entity = (id?.let { postRepository.findById(it).orElse(null) }) ?: LearningPostEntity().apply {
            this.id = id ?: UUID.randomUUID()
            this.createdBy = teacher.id
        }
        if (entity.createdBy != teacher.id) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your content")
        applyPost(entity, payload, teacher)
        postRepository.save(entity)
        return postPayload(entity, teacher.name)
    }

    /** Edits a material in place, preserving its id and analytics. */
    @Transactional
    fun updateMaterial(teacher: UserEntity, materialIdRaw: String, request: MaterialUpdateRequest): TeacherContentPayload {
        requireTeacher(teacher)
        val materialId = runCatching { UUID.fromString(materialIdRaw) }.getOrNull()
            ?: throw invalidArgument("material id is not a valid identifier")
        val post = postRepository.findById(runCatching { UUID.fromString(request.content.postId) }.getOrNull() ?: throw invalidArgument("post id is not a valid identifier"))
            .orElse(null) ?: throw notFound("Post not found")
        if (post.createdBy != teacher.id) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your content")
        val existing = contentRepository.findById(materialId).orElse(null)
        if (existing != null && existing.postId != post.id) throw notFound("Material not found")
        val content = existing ?: LearningContentEntity().apply {
            id = materialId
            this.postId = post.id
        }
        applyPost(post, request.post, teacher)
        postRepository.save(post)
        applyContent(content, request.content, post.id)
        contentRepository.save(content)
        return contentPayload(content)
    }

    /**
     * Publishes the parent post immediately, or holds it as SCHEDULED until the
     * supplied publish instant (docs/ongoing/api_content_changes.md section 1.3).
     */
    @Transactional
    fun publishMaterial(teacher: UserEntity, materialIdRaw: String, publishDate: Long?): TeacherContentPayload {
        requireTeacher(teacher)
        val content = ownContent(teacher, materialIdRaw)
        val post = postRepository.findById(content.postId).orElseThrow { notFound("Post not found") }
        val now = clock.instant()
        if (publishDate != null && publishDate > now.toEpochMilli()) {
            post.status = SCHEDULED
            post.publishAt = Instant.ofEpochMilli(publishDate)
            post.isPublished = false
        } else {
            post.status = PUBLISHED
            post.publishAt = null
            post.isPublished = true
        }
        post.updatedAt = now
        postRepository.save(post)
        return contentPayload(content)
    }

    /** Hides the material from learners without deleting it. Idempotent. */
    @Transactional
    fun archiveMaterial(teacher: UserEntity, materialIdRaw: String): TeacherContentPayload {
        requireTeacher(teacher)
        val content = ownContent(teacher, materialIdRaw)
        val post = postRepository.findById(content.postId).orElseThrow { notFound("Post not found") }
        post.status = ARCHIVED
        post.publishAt = null
        post.isPublished = false
        post.updatedAt = clock.instant()
        postRepository.save(post)
        return contentPayload(content)
    }

    /** Restores an archived material. Idempotent. */
    @Transactional
    fun unarchiveMaterial(teacher: UserEntity, materialIdRaw: String): TeacherContentPayload {
        requireTeacher(teacher)
        val content = ownContent(teacher, materialIdRaw)
        val post = postRepository.findById(content.postId).orElseThrow { notFound("Post not found") }
        post.status = PUBLISHED
        post.publishAt = null
        post.isPublished = true
        post.updatedAt = clock.instant()
        postRepository.save(post)
        return contentPayload(content)
    }

    /** Flips scheduled posts whose instant has arrived so learners can see them. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    fun publishDueContent() {
        postRepository.findAllByStatusAndPublishAtLessThanEqual(SCHEDULED, clock.instant())
            .forEach { post ->
                post.status = PUBLISHED
                post.isPublished = true
                post.publishAt = null
                post.updatedAt = clock.instant()
                postRepository.save(post)
            }
    }

    @Transactional
    fun deleteMaterial(teacher: UserEntity, materialIdRaw: String) {
        requireTeacher(teacher)
        val content = ownContent(teacher, materialIdRaw)
        contentRepository.delete(content)
        if (contentRepository.findAllByPostIdOrderByOrderIndexAsc(content.postId).isEmpty()) {
            postRepository.deleteById(content.postId)
        }
    }

    // ---------------------------------------------------------------- drafts

    @Transactional(readOnly = true)
    fun drafts(teacher: UserEntity): List<TeacherContentDraftPayload> =
        draftRepository.findAllByTeacherIdOrderByLastModifiedDesc(teacher.id).map(::draftPayload)

    @Transactional
    fun saveDraft(teacher: UserEntity, payload: TeacherContentDraftPayload): TeacherContentDraftPayload {
        requireTeacher(teacher)
        val clientId = payload.id.takeIf { it.isNotBlank() } ?: "draft_" + UUID.randomUUID()
        val row = draftRepository.findByClientId(clientId) ?: ContentDraftEntity().apply {
            this.clientId = clientId
            this.teacherId = teacher.id
        }
        if (row.teacherId != teacher.id) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your draft")
        row.contentType = materialType(payload.type)
        row.title = payload.title.trim()
        row.description = payload.description
        row.subject = payload.subject
        row.customSubjectName = payload.customSubjectName
        row.gradeLevel = payload.gradeLevel
        row.topic = payload.topic
        row.body = payload.body
        row.mediaUrls = codec.toJson(payload.mediaUrls.filter { it.isNotBlank() })
        row.tags = codec.toJson(payload.tags.filter { it.isNotBlank() })
        row.cbcStrands = codec.toJson(payload.cbcStrands.filter { it.isNotBlank() })
        row.difficulty = payload.difficulty.coerceIn(1, 5)
        row.estimatedMinutes = payload.estimatedMinutes.coerceAtLeast(0)
        row.lastModified = if (payload.lastModified > 0) Instant.ofEpochMilli(payload.lastModified) else clock.instant()
        row.authorName = teacher.name
        draftRepository.save(row)
        return draftPayload(row)
    }

    @Transactional
    fun deleteDraft(teacher: UserEntity, draftIdRaw: String) {
        val row = draftRepository.findByClientId(draftIdRaw) ?: return
        if (row.teacherId != teacher.id) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your draft")
        draftRepository.delete(row)
    }

    // ---------------------------------------------------------------- documents

    @Transactional(readOnly = true)
    fun documents(teacher: UserEntity): List<TeacherDocumentPayload> {
        requireTeacher(teacher)
        return fileRepository.findAllByCreatedByAndIsActiveTrueOrderByCreatedAtDesc(teacher.id).map(::documentPayload)
    }

    /** Idempotent by the client-supplied document id (multipart replays converge). */
    @Transactional
    fun uploadDocument(
        teacher: UserEntity,
        id: String?,
        title: String,
        description: String?,
        type: String,
        authorName: String?,
        gradeLevel: String?,
        subject: String?,
        topic: String?,
        fileSizeBytes: Long?,
        file: MultipartFile?,
    ): TeacherDocumentPayload {
        requireTeacher(teacher)
        if (title.isBlank()) throw invalidArgument("Document title is required")
        val docType = documentType(type)
        val clientId = id?.takeIf { it.isNotBlank() } ?: "doc_" + UUID.randomUUID()
        val existing = fileRepository.findByClientId(clientId)
        if (existing != null && existing.createdBy != teacher.id) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your document")
        val entity = existing ?: ReadableFileEntity().apply {
            this.clientId = clientId
            createdBy = teacher.id
        }
        if (file != null && !file.isEmpty) {
            val stored = mediaService.storeDocument(file)
            mediaService.delete(entity.fileUrl)
            entity.fileUrl = stored.url
            entity.sizeBytes = file.size
        } else if (fileSizeBytes != null) {
            entity.sizeBytes = fileSizeBytes
        }
        entity.title = title.trim()
        entity.description = description
        entity.authorName = authorName ?: teacher.name
        entity.gradeLevel = gradeLevel
        entity.subject = subject?.takeIf { it.isNotBlank() } ?: "GENERAL"
        entity.topic = topic
        entity.docType = docType
        entity.fileType = if (docType == "PLAINTEXT") FileType.TXT else FileType.PDF
        entity.scope = LearningScope.SCHOOL
        entity.schoolId = teacher.schoolId
        entity.isActive = true
        fileRepository.save(entity)
        return documentPayload(entity)
    }

    /** Repeat-safe: an unknown document is treated as already deleted. */
    @Transactional
    fun deleteDocument(teacher: UserEntity, documentIdRaw: String) {
        val entity = fileRepository.findByClientId(documentIdRaw)
            ?: runCatching { UUID.fromString(documentIdRaw) }.getOrNull()?.let { fileRepository.findById(it).orElse(null) }
            ?: return
        if (entity.createdBy != teacher.id) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your document")
        mediaService.delete(entity.fileUrl)
        fileRepository.delete(entity)
    }

    private fun documentPayload(entity: ReadableFileEntity) = TeacherDocumentPayload(
        id = entity.clientId ?: entity.id.toString(),
        title = entity.title,
        author = entity.authorName.orEmpty(),
        description = entity.description.orEmpty(),
        type = entity.docType ?: entity.fileType.name,
        source = DocumentSourcePayload(entity.fileUrl),
        sourcePath = entity.fileUrl,
        pageCount = entity.pageCount.takeIf { it > 0 },
        fileSizeBytes = entity.sizeBytes.takeIf { it > 0 },
        addedAt = entity.createdAt.toEpochMilli(),
        teacherId = entity.createdBy.toString(),
        grade = entity.gradeLevel,
        subject = entity.subject,
        scope = entity.scope.name,
        schoolId = entity.schoolId?.toString(),
    )

    private fun documentType(raw: String): String {
        val upper = raw.trim().uppercase()
        if (upper in DOCUMENT_TYPES) return upper
        throw invalidArgument("Unsupported document type: " + raw)
    }

    // ---------------------------------------------------------------- analytics

    @Transactional(readOnly = true)
    fun analytics(teacher: UserEntity, contentIdRaw: String): ContentAnalyticsPayload {
        requireTeacher(teacher)
        val content = ownContent(teacher, contentIdRaw)
        val postId = content.postId
        val views = viewRepository.countByPostId(postId).toInt()
        val progress = progressRepository.findAllByPostId(postId)
        val completions = progress.count { it.completed }
        val students = userRepository.findAllById(progress.map { it.userId }).associateBy { it.id }
        val scores = progress.mapNotNull { it.quizScore.takeIf { s -> s >= 0 }?.toDouble() }
        return ContentAnalyticsPayload(
            contentId = content.id.toString(),
            views = views,
            completions = completions,
            // Per-user time-on-content is not tracked server-side yet.
            averageTimeSpentSeconds = 0,
            averageQuizScore = if (scores.isEmpty()) null else (scores.average() * 100).toInt() / 100.0,
            engagementRate = if (views == 0) 0.0 else ((completions * 10000 / views) / 100.0),
            studentEngagement = progress.mapNotNull { row ->
                val student = students[row.userId] ?: return@mapNotNull null
                StudentEngagementPayload(
                    studentId = row.userId.toString(),
                    studentName = student.name,
                    status = if (row.completed) "COMPLETED" else "VIEWED",
                    timeSpentSeconds = 0,
                    lastAccessedAt = row.lastViewedAt.toEpochMilli(),
                    quizScore = row.quizScore.takeIf { it >= 0 },
                )
            }.sortedByDescending { it.lastAccessedAt },
        )
    }

    // ---------------------------------------------------------------- mapping

    private fun applyPost(entity: LearningPostEntity, payload: TeacherPostPayload, teacher: UserEntity) {
        entity.title = payload.title.trim()
        entity.subject = payload.subject.trim().ifBlank { "GENERAL" }
        entity.topic = payload.topic.takeIf { it.isNotBlank() }
        entity.subtopic = payload.subtopic.takeIf { it.isNotBlank() }
        entity.imageUrl = payload.imageUrl.takeIf { it.isNotBlank() }
        entity.description = payload.description.takeIf { it.isNotBlank() }
        entity.estimatedMinutes = payload.estimatedMinutes.coerceAtLeast(0)
        entity.difficulty = payload.difficulty.coerceIn(1, 5)
        entity.tags = codec.toJson(payload.tags.filter { it.isNotBlank() })
        entity.gradeLevel = payload.gradeLevel.takeIf { it.isNotBlank() }
        entity.scope = runCatching { LearningScope.valueOf(payload.scope.uppercase()) }.getOrDefault(LearningScope.SCHOOL_GRADE_CLASS)
        entity.schoolId = payload.schoolId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: teacher.schoolId
        entity.teacherId = teacher.id
        entity.cbcStrand = payload.cbcStrand
        entity.cbcSubStrand = payload.cbcSubStrand
        entity.customSubjectName = payload.customSubjectName
        entity.isFeatured = payload.isFeatured
        val status = normalizeStatus(payload.status, payload.isPublished)
        entity.status = status
        entity.isPublished = status == PUBLISHED
        if (status != SCHEDULED) entity.publishAt = null
        entity.updatedAt = clock.instant()
    }

    private fun applyContent(entity: LearningContentEntity, payload: TeacherContentPayload, postId: UUID) {
        entity.postId = postId
        entity.contentTypeLabel = materialType(payload.type)
        entity.contentType = canonicalType(payload.type)
        entity.title = payload.title.takeIf { it.isNotBlank() }
        entity.content = payload.content
        entity.durationMinutes = payload.durationMinutes.coerceAtLeast(0)
        entity.orderIndex = payload.orderIndex
        entity.thumbnailUrl = payload.thumbnailUrl
        entity.metadata = payload.metadata
    }

    private fun postPayload(entity: LearningPostEntity, authorName: String) = TeacherPostPayload(
        id = entity.id.toString(),
        title = entity.title,
        subject = entity.subject,
        topic = entity.topic.orEmpty(),
        subtopic = entity.subtopic.orEmpty(),
        gradeLevel = entity.gradeLevel.orEmpty(),
        imageUrl = entity.imageUrl.orEmpty(),
        description = entity.description.orEmpty(),
        estimatedMinutes = entity.estimatedMinutes,
        difficulty = entity.difficulty,
        tags = codec.parseList(entity.tags) ?: emptyList(),
        createdAt = entity.createdAt.toEpochMilli(),
        viewCount = entity.viewCount,
        likeCount = entity.likeCount,
        isFeatured = entity.isFeatured,
        isTrending = entity.viewCount >= TRENDING_VIEWS,
        cbcStrand = entity.cbcStrand,
        cbcSubStrand = entity.cbcSubStrand,
        authorName = authorName,
        scope = entity.scope.name,
        schoolId = entity.schoolId?.toString(),
        teacherId = entity.teacherId?.toString(),
        customSubjectName = entity.customSubjectName,
        isPublished = entity.isPublished,
        status = effectiveStatus(entity),
    )

    private fun contentPayload(entity: LearningContentEntity) = TeacherContentPayload(
        id = entity.id.toString(),
        postId = entity.postId.toString(),
        type = entity.contentTypeLabel ?: entity.contentType.name,
        title = entity.title.orEmpty(),
        content = entity.content.orEmpty(),
        durationMinutes = entity.durationMinutes,
        orderIndex = entity.orderIndex,
        thumbnailUrl = entity.thumbnailUrl,
        metadata = entity.metadata,
    )

    private fun draftPayload(entity: ContentDraftEntity) = TeacherContentDraftPayload(
        id = entity.clientId,
        teacherId = entity.teacherId.toString(),
        type = entity.contentType,
        title = entity.title,
        description = entity.description,
        subject = entity.subject,
        customSubjectName = entity.customSubjectName,
        gradeLevel = entity.gradeLevel,
        topic = entity.topic,
        body = entity.body,
        mediaUrls = codec.parseList(entity.mediaUrls) ?: emptyList(),
        tags = codec.parseList(entity.tags) ?: emptyList(),
        cbcStrands = codec.parseList(entity.cbcStrands) ?: emptyList(),
        difficulty = entity.difficulty,
        estimatedMinutes = entity.estimatedMinutes,
        lastModified = entity.lastModified.toEpochMilli(),
        authorName = entity.authorName.orEmpty(),
    )

    private fun ownContent(teacher: UserEntity, raw: String): LearningContentEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument("material id is not a valid identifier")
        val content = contentRepository.findById(id).orElse(null) ?: throw notFound("Material not found")
        val post = postRepository.findById(content.postId).orElse(null) ?: throw notFound("Post not found")
        if (post.createdBy != teacher.id) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your content")
        return content
    }

    /** Validates the client material type and returns its canonical label. */
    private fun materialType(raw: String): String {
        val upper = raw.trim().uppercase()
        if (upper in MATERIAL_TYPES) return upper
        throw invalidArgument("Unknown content type: " + raw)
    }

    /** Maps a client material type onto the canonical learning_content enum. */
    private fun canonicalType(raw: String): ContentType = when (materialType(raw)) {
        "NOTES" -> ContentType.NOTES
        "VIDEO" -> ContentType.VIDEO
        "QUIZ" -> ContentType.QUIZ
        "DIAGRAM" -> ContentType.DIAGRAM
        else -> ContentType.DOCUMENT
    }

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
    }

    private fun normalizeStatus(raw: String?, published: Boolean): String {
        val value = raw?.trim()?.uppercase().orEmpty()
        if (value in STATUSES) return value
        return if (published) PUBLISHED else ARCHIVED
    }

    /** A scheduled post whose instant has passed reports (and files) as PUBLISHED. */
    private fun effectiveStatus(entity: LearningPostEntity): String {
        val status = entity.status
        val due = entity.publishAt
        if (status == SCHEDULED && due != null && !due.isAfter(clock.instant())) return PUBLISHED
        return status
    }

    private companion object {
        const val TRENDING_VIEWS = 100
        const val PUBLISHED = "PUBLISHED"
        const val SCHEDULED = "SCHEDULED"
        const val ARCHIVED = "ARCHIVED"
        val STATUSES = setOf(PUBLISHED, SCHEDULED, ARCHIVED)
        val MATERIAL_TYPES = setOf("NOTES", "VIDEO", "QUIZ", "FLASHCARDS", "PDF", "EPUB", "PLAINTEXT", "DIAGRAM", "DOCUMENT")
        val DOCUMENT_TYPES = setOf("PDF", "EPUB", "PLAINTEXT")
    }
}
