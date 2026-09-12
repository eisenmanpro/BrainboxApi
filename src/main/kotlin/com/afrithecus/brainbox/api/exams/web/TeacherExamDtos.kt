package com.afrithecus.brainbox.api.exams.web

/**
 * Teacher digital exam payloads. Field names and shapes mirror
 * com.afrithecus.brainbox.teacher.models.TeacherModels and
 * teacher/data/remote/TeacherExamApi exactly, because the app caches these
 * objects in Room and replays offline writes verbatim.
 */

data class TeacherExamSectionPayload(
    val id: String = "",
    val examId: String = "",
    val title: String = "",
    val instructions: String? = null,
    val durationMinutes: Int? = null,
    val sortOrder: Int = 0,
)

data class TeacherQuestionPayload(
    val id: String = "",
    val examId: String = "",
    val sectionId: String? = null,
    val questionText: String = "",
    val questionType: String = "MCQ",
    val points: Int = 0,
    val difficulty: Int = 3,
    val options: List<String>? = null,
    val correctAnswer: String? = null,
    val cbcStrandTag: String? = null,
    val sortOrder: Int = 0,
    val isKeyQuestion: Boolean = false,
    val requiresExplanation: Boolean = false,
    val isFromBank: Boolean = false,
)

data class TeacherExamPayload(
    val id: String = "",
    val classId: String = "",
    val teacherId: String = "",
    val schoolId: String = "",
    val title: String = "",
    val subject: String = "",
    val gradeLevel: Int = 0,
    val durationMinutes: Int = 0,
    val totalPoints: Int = 0,
    val difficulty: Int = 3,
    val questions: List<TeacherQuestionPayload> = emptyList(),
    val sections: List<TeacherExamSectionPayload> = emptyList(),
    val openDate: Long? = null,
    val closeDate: Long? = null,
    val isPublished: Boolean = false,
    val createdAt: Long = 0,
    val scope: String = "SCHOOL_GRADE",
    val term: String = "",
)

/** Body of POST teacher/exams/review/{submissionId}/{questionId}. */
data class ReviewMarkPayload(val mark: Int = 0)

data class TeacherReviewQueueItemPayload(
    val submissionId: String,
    val examId: String,
    val studentId: String,
    val studentName: String,
    val questionId: String,
    val questionText: String,
    val studentAnswer: String,
    val correctAnswer: String,
    val suggestedMark: Int,
    val maxMark: Int,
    val requiresTeacherReview: Boolean = true,
    val requiresExplanation: Boolean = false,
    val cbcStrand: String? = null,
)

data class WeakAreaPayload(
    val cbcStrand: String,
    val masteryLevel: Double,
    val severity: String,
    val affectedStudents: Int,
    val recommendedAction: String,
)

data class QuestionStatsPayload(
    val questionId: String,
    val questionText: String,
    val cbcStrand: String? = null,
    val correctCount: Int,
    val incorrectCount: Int,
    val skipCount: Int,
    val masteryPercentage: Double,
    val flaggedByTeacher: Boolean,
)

data class KeyQuestionsSummaryPayload(
    val examId: String,
    val className: String,
    val totalStudents: Int,
    val questions: List<QuestionStatsPayload> = emptyList(),
)

data class StudentExamPerformancePayload(
    val studentId: String,
    val studentName: String,
    val score: Int,
    val maxScore: Int,
    val percentage: Double,
    val rank: Int? = null,
    val weakAreas: List<WeakAreaPayload> = emptyList(),
    val questionBreakdown: List<QuestionGradingDetailPayload> = emptyList(),
)

data class ExamAnalysisReportPayload(
    val examId: String,
    val className: String,
    val totalStudents: Int,
    val averageScore: Double,
    val medianScore: Double,
    val standardDeviation: Double,
    val highestScore: Int,
    val lowestScore: Int,
    val passRate: Double,
    val keyQuestionsSummary: KeyQuestionsSummaryPayload,
    val weakAreas: List<WeakAreaPayload> = emptyList(),
    val studentPerformances: List<StudentExamPerformancePayload> = emptyList(),
)

data class RemediationAssignmentPayload(
    val cbcStrand: String,
    val action: String,
    val assignedBy: String? = null,
    val assignedAt: Long? = null,
)

data class RemediationSavePayload(
    val assignments: List<RemediationAssignmentPayload> = emptyList(),
)
