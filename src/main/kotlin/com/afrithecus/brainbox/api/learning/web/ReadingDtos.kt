package com.afrithecus.brainbox.api.learning.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

// ---------------------------------------------------------------------------
// Reading materials, reading + learning progress payloads (doc 03 §3-§5).
// ---------------------------------------------------------------------------

data class CreateReadableRequest(
    @field:NotBlank
    val title: String,
    @field:NotBlank
    val subject: String,
    val category: String? = null,
    @field:NotBlank
    val fileUrl: String,
    @field:NotBlank
    val fileType: String,
    @field:Min(0)
    val pageCount: Int = 0,
    val sizeBytes: Long = 0,
    val scope: String = "GLOBAL",
    val schoolId: String? = null,
    val gradeLevel: String? = null,
)

data class ReadableFilePayload(
    val id: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val subject: String,
    val category: String? = null,
    /** Android `ReadableFile.filePath` (was `fileUrl`, which the client cannot map). */
    val filePath: String,
    /**
     * Inline markdown body for a generated quick-read chunk (section F of the phase-7
     * client doc). Null for file-backed materials; a chunk has an empty [filePath] and
     * carries its content here instead.
     */
    val body: String? = null,
    /** PDF | EPUB | PLAINTEXT, matching the Android `ReadableFileType`. */
    val fileType: String,
    /** Android `ReadableFile.totalPages` (was `pageCount`). */
    val totalPages: Int,
    val thumbnailUrl: String? = null,
    val isFromAssets: Boolean = false,
    /** Android `ReadableFile.fileSize` (was `sizeBytes`). */
    val fileSize: Long,
    val version: Int,
    val createdAt: Long,
)

data class ReadingProgressRequest(
    @field:NotBlank
    val fileId: String,
    @field:Min(0)
    val currentPage: Int,
    @field:Min(0)
    val totalPages: Int,
    val lastReadAt: Long? = null,
)

data class ReadingProgressPayload(
    val fileId: String,
    val currentPage: Int,
    val totalPages: Int,
    val lastReadAt: Long,
)

data class ReadingSessionRequest(
    @field:NotBlank
    val fileId: String,
    val startTime: Long,
    val endTime: Long,
    @field:Min(0)
    val pagesRead: Int = 0,
)

data class ReadingSessionPayload(
    val fileId: String,
    val startTime: Long,
    val endTime: Long,
    val pagesRead: Int,
)

data class LearningProgressRequest(
    @field:NotBlank
    val postId: String,
    @field:Min(-1) @field:Max(100)
    val quizScore: Int = -1,
    val lastViewedAt: Long? = null,
    val completed: Boolean = false,
    val answersJson: String? = null,
)

data class LearningProgressPayload(
    val postId: String,
    val quizScore: Int,
    val lastViewedAt: Long,
    val completed: Boolean,
)

data class ContinueLearningItem(
    val postId: String,
    val title: String,
    val subject: String,
    val progress: Int,
    val lastViewedAt: Long,
    val estimatedMinutesRemaining: Int,
)

data class RecommendationPayload(
    val postId: String,
    val title: String,
    val subject: String,
    val reason: String,
    val priority: String,
)
