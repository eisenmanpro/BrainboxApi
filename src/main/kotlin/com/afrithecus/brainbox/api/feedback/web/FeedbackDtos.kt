package com.afrithecus.brainbox.api.feedback.web

/** Teacher feedback payloads matching teacher/models/TeacherModels.kt exactly. */

data class FeedbackTemplatePayload(
    val id: String = "",
    val teacherId: String = "",
    val title: String = "",
    val content: String = "",
    val category: String = "",
)

data class TeacherFeedbackPayload(
    val id: String = "",
    val submissionId: String = "",
    val teacherId: String = "",
    val studentId: String = "",
    val textFeedback: String? = null,
    val voiceFeedbackUrl: String? = null,
    val photoFeedbackUrls: List<String> = emptyList(),
    val rubricScores: Map<String, Int> = emptyMap(),
    val createdAt: Long = 0,
)
