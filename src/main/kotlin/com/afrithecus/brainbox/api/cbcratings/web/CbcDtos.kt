package com.afrithecus.brainbox.api.cbcratings.web

/** CBC analytics payloads matching teacher/models/TeacherModels.kt and models/ParentModels.kt. */

data class CbcStrandInfoPayload(
    val code: String,
    val name: String,
    val descriptor: String,
    val gradeLevel: String,
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
)

data class CbcStrandRatingPayload(
    val strandCode: String,
    val descriptor: String,
    val rating: String,
    val evidenceLink: String? = null,
)

data class PaperExamResultPayload(
    val subject: String,
    val score: Double,
    val maxScore: Double,
    val percentage: Double,
    val grade: String,
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
