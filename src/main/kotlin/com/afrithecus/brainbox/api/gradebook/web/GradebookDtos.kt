package com.afrithecus.brainbox.api.gradebook.web

import com.afrithecus.brainbox.api.traditional.model.ExamTerm

// ---------------------------------------------------------------------------
// Gradebook payloads matching the Android models exactly
// (teacher/models/TeacherModels.kt and models/PublishedGrade.kt).
// ---------------------------------------------------------------------------

data class GradebookAssessmentPayload(
    val id: String,
    val classId: String,
    val title: String,
    val assessmentType: String,
    val maxScore: Int,
    val dateAssigned: Long,
    val cbcStrandTag: String? = null,
    val term: ExamTerm = ExamTerm.TERM_1,
    val isPublished: Boolean = false,
    val countsTowardAverage: Boolean = true,
    val createdBy: String = "",
)

data class GradebookEntryPayload(
    val id: String,
    val classId: String,
    val teacherId: String,
    val assessmentId: String,
    val assessmentType: String,
    val studentId: String,
    val studentName: String,
    val rawScore: Int,
    val maxScore: Int,
    val percentage: Int,
    val assessmentTitle: String? = null,
    val cbcStrandTag: String? = null,
    val teacherNote: String? = null,
    val gradedAt: Long,
)

/** Learner/parent published grade (models/PublishedGrade.kt). */
data class PublishedGradePayload(
    val id: String,
    val assessmentId: String,
    val assessmentTitle: String,
    val assessmentType: String,
    val term: String,
    val score: Int,
    val maxScore: Int,
    val percentage: Int,
    val gradeBand: String,
    val teacherNote: String? = null,
    val gradedAt: Long,
    val countsTowardAverage: Boolean = true,
)
