package com.afrithecus.brainbox.api.learning

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.learning.entity.ReadableFileEntity
import com.afrithecus.brainbox.api.learning.entity.ReadingProgressEntity
import com.afrithecus.brainbox.api.learning.entity.ReadingSessionEntity
import com.afrithecus.brainbox.api.learning.model.FileType
import com.afrithecus.brainbox.api.learning.model.LearningScope
import com.afrithecus.brainbox.api.learning.repository.ReadableFileRepository
import com.afrithecus.brainbox.api.learning.repository.ReadingProgressRepository
import com.afrithecus.brainbox.api.learning.repository.ReadingSessionRepository
import com.afrithecus.brainbox.api.learning.web.CreateReadableRequest
import com.afrithecus.brainbox.api.learning.web.ReadableFilePayload
import com.afrithecus.brainbox.api.learning.web.ReadingProgressPayload
import com.afrithecus.brainbox.api.learning.web.ReadingProgressRequest
import com.afrithecus.brainbox.api.learning.web.ReadingSessionPayload
import com.afrithecus.brainbox.api.learning.web.ReadingSessionRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Reading materials + progress + sessions (doc 03 §3). */
@Service
class MaterialsService(
    private val fileRepository: ReadableFileRepository,
    private val progressRepository: ReadingProgressRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val clock: Clock,
) {

    // ------------------------------------------------------------ discovery

    @Transactional(readOnly = true)
    fun list(user: UserEntity, category: String? = null): List<ReadableFilePayload> =
        visible(user).filter { category == null || it.category == category }
            .sortedBy { it.title.lowercase() }
            .map(::toPayload)

    @Transactional(readOnly = true)
    fun search(user: UserEntity, query: String): List<ReadableFilePayload> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return visible(user).filter { file ->
            val category = file.category
            file.title.contains(q, ignoreCase = true) ||
                (category != null && category.contains(q, ignoreCase = true)) ||
                file.subject.contains(q, ignoreCase = true)
        }.sortedBy { it.title.lowercase() }.map(::toPayload)
    }

    @Transactional(readOnly = true)
    fun detail(user: UserEntity, fileIdRaw: String): ReadableFilePayload =
        toPayload(findVisible(user, fileIdRaw))

    // ------------------------------------------------- reading progress

    @Transactional
    fun upsertProgress(user: UserEntity, request: ReadingProgressRequest): ReadingProgressPayload {
        if (request.currentPage > request.totalPages) {
            throw invalidArgument("currentPage cannot exceed totalPages")
        }
        val file = findVisible(user, request.fileId)
        val now = clock.instant()
        val lastReadAt = request.lastReadAt?.let(Instant::ofEpochMilli) ?: now
        val existing = progressRepository.findByUserIdAndFileId(user.id, file.id)
        if (existing != null && existing.lastReadAt.isAfter(lastReadAt)) {
            return toProgress(existing) // keep the newest read position (idempotent upsert)
        }
        val row = (existing ?: ReadingProgressEntity().apply {
            this.fileId = file.id
            this.userId = user.id
        }).apply {
            currentPage = request.currentPage
            totalPages = request.totalPages
            this.lastReadAt = lastReadAt
            updatedAt = now
        }
        progressRepository.save(row)
        return toProgress(row)
    }

    @Transactional(readOnly = true)
    fun progress(user: UserEntity, fileIdRaw: String): ReadingProgressPayload? {
        val file = findVisible(user, fileIdRaw)
        return progressRepository.findByUserIdAndFileId(user.id, file.id)?.let(::toProgress)
    }

    // --------------------------------------------------- reading sessions

    @Transactional
    fun recordSession(user: UserEntity, request: ReadingSessionRequest): ReadingSessionPayload {
        val file = findVisible(user, request.fileId)
        val start = Instant.ofEpochMilli(request.startTime)
        val end = Instant.ofEpochMilli(request.endTime)
        if (!end.isAfter(start)) throw invalidArgument("endTime must be after startTime")
        if (java.time.Duration.between(start, end).toHours() > MAX_SESSION_HOURS) {
            throw invalidArgument("reading session is unreasonably long")
        }
        val session = ReadingSessionEntity().apply {
            this.fileId = file.id
            this.userId = user.id
            startTime = start
            endTime = end
            pagesRead = request.pagesRead
            createdAt = clock.instant()
        }
        sessionRepository.save(session)
        return ReadingSessionPayload(
            fileId = file.id.toString(),
            startTime = start.toEpochMilli(),
            endTime = end.toEpochMilli(),
            pagesRead = session.pagesRead,
        )
    }

    @Transactional(readOnly = true)
    fun sessions(user: UserEntity, fileIdRaw: String): List<ReadingSessionPayload> {
        val file = findVisible(user, fileIdRaw)
        return sessionRepository.findAllByUserIdAndFileIdOrderByStartTimeDesc(user.id, file.id)
            .map {
                ReadingSessionPayload(
                    fileId = file.id.toString(),
                    startTime = it.startTime.toEpochMilli(),
                    endTime = it.endTime.toEpochMilli(),
                    pagesRead = it.pagesRead,
                )
            }
    }

    // ------------------------------------------------------------ authoring

    @Transactional
    fun createReadable(authorUserId: UUID, request: CreateReadableRequest): ReadableFilePayload {
        val fileType = runCatching { FileType.valueOf(request.fileType.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("fileType must be PDF, DOCX, TXT or IMAGE")
        val scope = runCatching { LearningScope.valueOf(request.scope.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("scope must be GLOBAL, SCHOOL or SCHOOL_GRADE_CLASS")
        if (scope != LearningScope.GLOBAL && request.schoolId == null) {
            throw invalidArgument("scope " + scope.name + " requires a schoolId")
        }
        val file = ReadableFileEntity().apply {
            title = request.title.trim()
            subject = request.subject.trim()
            category = request.category?.trim()?.takeIf { it.isNotEmpty() }
            fileUrl = request.fileUrl.trim()
            this.fileType = fileType
            pageCount = request.pageCount
            sizeBytes = request.sizeBytes
            this.scope = scope
            schoolId = request.schoolId?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            gradeLevel = request.gradeLevel?.trim()?.takeIf { it.isNotEmpty() }
            createdBy = authorUserId
        }
        fileRepository.save(file)
        return toPayload(file)
    }

    // ------------------------------------------------------------ authoring extras

    @Transactional(readOnly = true)
    fun adminList(): List<ReadableFilePayload> =
        fileRepository.findAll().sortedBy { it.title.lowercase() }.map(::toPayload)

    @Transactional
    fun deactivateReadable(fileIdRaw: String) {
        val id = runCatching { UUID.fromString(fileIdRaw) }.getOrNull()
            ?: throw invalidArgument("file id is not a valid identifier")
        val file = fileRepository.findById(id).orElseThrow { notFound("File not found") }
        file.isActive = false
        fileRepository.save(file)
    }

    // ------------------------------------------------------------ internals

    private fun visible(user: UserEntity): List<ReadableFileEntity> =
        fileRepository.findAllByIsActiveTrue().filter { file ->
            ContentScope.isVisible(file.scope, file.schoolId, file.gradeLevel, null, user)
        }

    private fun findVisible(user: UserEntity, raw: String): ReadableFileEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument("file id is not a valid identifier")
        val file = fileRepository.findById(id).orElse(null) ?: throw notFound("File not found")
        if (!file.isActive || !ContentScope.isVisible(file.scope, file.schoolId, file.gradeLevel, null, user)) {
            throw notFound("File not found")
        }
        return file
    }

    private fun toPayload(file: ReadableFileEntity) = ReadableFilePayload(
        id = file.id.toString(),
        title = file.title,
        subject = file.subject,
        category = file.category,
        fileUrl = file.fileUrl,
        fileType = file.fileType.name,
        pageCount = file.pageCount,
        sizeBytes = file.sizeBytes,
        version = file.fileVersion,
        createdAt = file.createdAt.toEpochMilli(),
    )

    private fun toProgress(row: ReadingProgressEntity) = ReadingProgressPayload(
        fileId = row.fileId.toString(),
        currentPage = row.currentPage,
        totalPages = row.totalPages,
        lastReadAt = row.lastReadAt.toEpochMilli(),
    )

    private companion object {
        const val MAX_SESSION_HOURS = 8L
    }
}
