package com.afrithecus.brainbox.api.learning.web

/** Source descriptor for a teacher document (models/DocumentModels.kt DocumentSource). */
data class DocumentSourcePayload(val url: String)

/**
 * Teacher document payload (models/DocumentModels.kt DocumentItem). `source` mirrors
 * DocumentSource.Remote; sourceType/sourcePath are the client repository's flattened
 * form so either mapper has what it needs.
 */
data class TeacherDocumentPayload(
    val id: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val type: String,
    val source: DocumentSourcePayload,
    val sourceType: String = "REMOTE",
    val sourcePath: String,
    val coverUrl: String? = null,
    val pageCount: Int? = null,
    val fileSizeBytes: Long? = null,
    val isBundled: Boolean = false,
    val addedAt: Long = 0,
    val teacherId: String? = null,
    val code: String? = null,
    val isPastPaper: Boolean = false,
    val grade: String? = null,
    val subject: String? = null,
    val isLocked: Boolean = false,
    val scope: String = "SCHOOL",
    val schoolId: String? = null,
)
