package com.afrithecus.brainbox.api.exams.web

/**
 * Past-paper content payload (doc 02 §4.2). Raw JSON matching the Android
 * ExamContent contract; sections carry a discriminant "type" tag so the client
 * parser can pick the right sealed subtype. Answer keys travel inside
 * markingScheme for v1 offline self-grading (doc 02 §4.2 obligation 3).
 */
data class ExamContentPayload(
    val examId: String,
    val cover: ExamCoverPayload,
    val sections: List<ExamSectionPayload>,
    val markingScheme: MarkingSchemePayload,
    val gradingMode: String = "AUTO_IMMEDIATE",
)

data class ExamCoverPayload(
    val schoolName: String,
    val studentName: String,
    val examId: String,
    val time: String,
    val questionCount: Int,
    val year: Int,
    val mcp: Boolean,
    val subject: String,
)

data class ExamSectionPayload(
    val type: String,
    val instructions: String? = null,
    val content: String? = null,
    val diagram: ExamDiagramPayload? = null,
    val questions: List<ExamContentQuestionPayload>,
)

data class ExamDiagramPayload(
    val id: String,
    val type: String,
    val content: String,
    val caption: String? = null,
    val version: Int = 1,
)

data class ExamContentQuestionPayload(
    val id: String,
    val text: String,
    val type: String,
    val options: List<String>? = null,
    val correctAnswer: String = "",
    val explanation: String = "",
    val points: Int,
    val difficulty: Int,
    val matchingPairs: Map<String, String>? = null,
    val number: Int,
    val topic: String = "",
    val subtopic: String? = null,
)

data class MarkingSchemePayload(
    val questionAnswers: Map<String, String>,
    val questionMarks: Map<String, Int>,
    val totalMarks: Int,
    val passingScore: Int,
)
