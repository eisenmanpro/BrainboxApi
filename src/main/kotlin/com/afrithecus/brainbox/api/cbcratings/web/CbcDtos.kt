package com.afrithecus.brainbox.api.cbcratings.web

/** CBC analytics payloads matching teacher/models/TeacherModels.kt and models/ParentModels.kt. */

data class CbcStrandInfoPayload(
    val code: String,
    val name: String,
    val descriptor: String,
    val gradeLevel: String,
    /** Subject the strand belongs to (Mathematics, Integrated Science, ...). */
    val subject: String = "",
    /** STRAND or SUBSTRAND; a picker shows a sub-strand under its parent. */
    val level: String = "STRAND",
    /** Code of the parent strand for a sub-strand; null for a top-level strand. */
    val parentCode: String? = null,
)

data class CbcCurriculumMapPayload(
    val strands: List<CbcStrandInfoPayload> = emptyList(),
)

data class CbcStrandMasteryPayload(
    val strandCode: String,
    val strandName: String,
    val classAverage: Double,
    val studentBreakdown: Map<String, String> = emptyMap(),
    val isWeakStrand: Boolean = false,
    val recommendation: String? = null,
    /**
     * Exact letter ratings per learner (EE/ME/AE/BE) for this strand (F50). [studentBreakdown]
     * collapses MEETING and APPROACHING into one mastery word, so the class report cannot tell
     * an ME learner from an AE one; this map does. Additive: older clients ignore it, and it is
     * last so existing positional constructions keep working.
     */
    val studentRatings: Map<String, String> = emptyMap(),
)

data class SubjectTeacherPerformancePayload(
    val subject: String,
    val teacherId: String,
    val teacherName: String,
    val classAverage: Double,
    val studentCount: Int,
    val strandScores: Map<String, Double> = emptyMap(),
)

data class CbcClassReportPayload(
    val classId: String,
    val className: String,
    val term: String,
    val strandMastery: List<CbcStrandMasteryPayload> = emptyList(),
    val overallClassAverage: Double = 0.0,
    val subjectTeacherPerformance: List<SubjectTeacherPerformancePayload> = emptyList(),
    val generatedAt: Long = 0,
    /** Real roster size, so class KPIs never invent a learner count (D2/F14). */
    val studentCount: Int = 0,
)

data class CbcStrandRatingPayload(
    val strandCode: String,
    val descriptor: String,
    val rating: String,
    val evidenceLink: String? = null,
    /** Parent-facing learning-area name resolved from the strand catalogue (D1/F6). */
    val strandName: String? = null,
)

data class PaperExamResultPayload(
    val subject: String,
    val score: Double,
    val maxScore: Double,
    val percentage: Double,
    val grade: String,
    /** The single latest published paper exam these rows belong to (D7). */
    val examId: String = "",
    val examTitle: String = "",
    val term: String = "",
    val year: Int = 0,
    val examDate: String = "",
)

data class CbcReportCardPayload(
    val studentName: String,
    val term: String,
    val strandRatings: List<CbcStrandRatingPayload> = emptyList(),
    val teacherComments: String = "",
    val attendancePercentage: Double = 0.0,
    val overallGrade: String = "ME",
    val paperExamResults: List<PaperExamResultPayload> = emptyList(),
    val gradeLevel: String? = null,
    /** Learner meta used by the printed card; null when the record has none. */
    val admissionNumber: String? = null,
)

data class StudentStrandRatingPayload(
    val studentId: String,
    val studentName: String,
    val rating: String,
    val trend: String = "STABLE",
    val lastUpdated: Long = 0,
)

data class StrandAssessmentPayload(
    val id: String,
    val title: String,
    val date: Long,
    val averageScore: Double,
    val type: String = "EXAM",
)

data class StrandMasteryDetailPayload(
    val strandCode: String,
    val strandName: String,
    val descriptor: String,
    val gradeLevel: String,
    val classPerformance: Map<String, Int> = emptyMap(),
    val studentRatings: List<StudentStrandRatingPayload> = emptyList(),
    val assessmentHistory: List<StrandAssessmentPayload> = emptyList(),
    val recommendedResources: List<Any?> = emptyList(),
)
