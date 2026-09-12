package com.afrithecus.brainbox.api.learning.web

// ---------------------------------------------------------------------------
// Teacher content-management payloads (docs/ongoing/api_content_changes.md),
// matching models/LearningHubModels.kt, models/DocumentModels.kt and
// teacher/models/TeacherContentModels.kt.
// ---------------------------------------------------------------------------

data class TeacherPostPayload(
    val id: String,
    val title: String,
    val subject: String,
    val topic: String = "",
    val subtopic: String = "",
    val gradeLevel: String = "",
    val imageUrl: String = "",
    val description: String = "",
    val estimatedMinutes: Int = 0,
    val difficulty: Int = 3,
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0,
    val viewCount: Int = 0,
    val likeCount: Int = 0,
    val isFeatured: Boolean = false,
    val isTrending: Boolean = false,
    val cbcStrand: String? = null,
    val cbcSubStrand: String? = null,
    val authorName: String = "",
    val scope: String = "GLOBAL",
    val schoolId: String? = null,
    val teacherId: String? = null,
    val customSubjectName: String? = null,
    val isPublished: Boolean = true,
    /** PUBLISHED | SCHEDULED | ARCHIVED; archived posts are hidden from learners. */
    val status: String = "PUBLISHED",
)

data class TeacherContentPayload(
    val id: String,
    val postId: String,
    val type: String,
    val title: String = "",
    val content: String = "",
    val durationMinutes: Int = 0,
    val orderIndex: Int = 0,
    val thumbnailUrl: String? = null,
    val metadata: String? = null,
)

data class TeacherContentDraftPayload(
    val id: String,
    val teacherId: String,
    val type: String,
    val title: String,
    val description: String? = null,
    val subject: String? = null,
    val customSubjectName: String? = null,
    val gradeLevel: String? = null,
    val topic: String? = null,
    val body: String? = null,
    val mediaUrls: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val cbcStrands: List<String> = emptyList(),
    val difficulty: Int = 1,
    val estimatedMinutes: Int = 0,
    val lastModified: Long = 0,
    val authorName: String = "",
)

data class StudentEngagementPayload(
    val studentId: String,
    val studentName: String,
    val status: String,
    val timeSpentSeconds: Long,
    val lastAccessedAt: Long,
    val quizScore: Int? = null,
)

data class ContentAnalyticsPayload(
    val contentId: String,
    val views: Int,
    val completions: Int,
    val averageTimeSpentSeconds: Long,
    val averageQuizScore: Double? = null,
    val engagementRate: Double,
    val studentEngagement: List<StudentEngagementPayload> = emptyList(),
    val isStale: Boolean = false,
)

/** Body of PUT teacher/content/material/{materialId}. */
data class MaterialUpdateRequest(
    val post: TeacherPostPayload,
    val content: TeacherContentPayload,
)
