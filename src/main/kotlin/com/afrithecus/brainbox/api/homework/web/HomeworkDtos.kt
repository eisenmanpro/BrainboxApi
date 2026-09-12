package com.afrithecus.brainbox.api.homework.web

import com.afrithecus.brainbox.api.exams.web.QuestionPayload
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode

// ---------------------------------------------------------------------------
// Homework payloads (web homework contract).
// ---------------------------------------------------------------------------

data class HomeworkQuestionRequest(
    @field:NotBlank
    val text: String,
    @field:NotBlank
    val type: String,
    val options: List<String>? = null,
    val correctAnswer: String? = null,
    val explanation: String? = null,
    @field:Min(1)
    val points: Int = 1,
)

data class HomeworkUpsertRequest(
    @field:NotBlank
    @field:Size(max = 128)
    val id: String,
    @field:NotBlank
    val classId: String,
    @field:Size(min = 3, max = 120)
    val title: String,
    @field:Size(min = 10, max = 2000)
    val description: String,
    @field:NotBlank
    val subject: String,
    @field:Min(1) @field:Max(12)
    val gradeLevel: Int,
    /** Explicit term; the server derives it from the creation month when absent. */
    val term: ExamTerm? = null,
    val dueDate: Long,
    @field:NotBlank
    val submissionType: String,
    @field:Size(max = 20)
    val checklistItems: List<String>? = null,
    @field:Size(max = 100)
    val questions: List<HomeworkQuestionRequest>? = null,
    val gradingMode: String? = null,
    val isPastPaperUnlocked: Boolean = false,
    @field:Size(max = 64)
    val relatedPaperCode: String? = null,
    @field:Size(max = 64)
    val relatedDocumentId: String? = null,
    val cbcStrandTag: String? = null,
    @field:Size(max = 60)
    val cbcSubStrandTag: String? = null,
    val assignedStudentIds: List<String>? = null,
    val scope: String = "SCHOOL_GRADE_CLASS",
    val isDraft: Boolean = false,
    val isActive: Boolean = true,
)

/** Response: homework + (student side) the caller's submission state. */
data class HomeworkPayload(
    val id: String,
    val classId: String,
    val teacherId: String,
    val teacherName: String,
    val schoolId: String? = null,
    val title: String,
    val description: String,
    val subject: String,
    val gradeLevel: Int,
    val term: ExamTerm? = null,
    val dueDate: Long,
    val submissionType: String,
    val checklistItems: List<String>? = null,
    val gradingMode: String? = null,
    val isPastPaperUnlocked: Boolean,
    val relatedPaperCode: String? = null,
    val relatedDocumentId: String? = null,
    val cbcStrandTag: String? = null,
    val cbcSubStrandTag: String? = null,
    val assignedStudentIds: List<String>? = null,
    val scope: String,
    val isDraft: Boolean,
    val isActive: Boolean,
    val createdAt: Long,
    // question-set content; keys only ever included in teacher payloads
    val questions: List<QuestionPayload>? = null,
    // student-side extras (absent for teachers via non-null exclusion)
    val submissionStatus: String? = null,
    val grade: Int? = null,
)

data class GradeSubmissionRequest(
    @field:Min(0) @field:Max(100)
    val grade: Int,
    val feedback: String? = null,
    val cbcStrandTag: String? = null,
    /** Client write time (ms); an older value never overwrites a newer server grade. */
    val clientTimestamp: Long? = null,
)

data class ReturnSubmissionRequest(
    val feedback: String? = null,
    val clientTimestamp: Long? = null,
)

/** One row of POST teacher/homework/{homeworkId}/grade-bulk. */
data class BulkGradeItem(
    val submissionId: String,
    @field:Min(0) @field:Max(100)
    val grade: Int,
    val feedback: String? = null,
    val clientTimestamp: Long? = null,
)

data class SubmissionPayload(
    val id: String,
    val homeworkId: String,
    val studentId: String,
    val studentName: String,
    val submissionText: String? = null,
    val attachmentUrl: String? = null,
    val status: String,
    val submittedAt: Long,
    val grade: Int? = null,
    val feedback: String? = null,
    val cbcStrandTag: String? = null,
    val gradedAt: Long? = null,
    val isGraded: Boolean = false,
)

data class HomeworkProgressItem(
    val homeworkId: String,
    val total: Int,
    val graded: Int,
    val pending: Int,
    val avg: Int? = null,
)

data class StudentSubmitRequest(
    val submissionText: String? = null,
    val checklistAnswers: List<Int>? = null,
    /** The client sends checklist indices as a comma-separated string. */
    val answerNotes: String? = null,
    val attachmentUrl: String? = null,
    val clientSubmissionId: String? = null,
    val answers: JsonNode? = null,
    // Tolerated client fields (the body is the full Homework model).
    val status: String? = null,
    val studentId: String? = null,
)

/**
 * The learner-facing Homework shape (models/Homework.kt). Field names differ from
 * the teacher [HomeworkPayload] (type/taskSteps/gradeLevel-as-string) so the client
 * maps it directly.
 */
data class StudentHomeworkPayload(
    val id: String,
    val title: String,
    val description: String,
    val subject: String,
    val dueDate: Long,
    val teacherId: String,
    val teacherName: String,
    val studentId: String? = null,
    val status: String,
    val grade: Int? = null,
    val feedback: String? = null,
    val type: String,
    val relatedDocumentId: String? = null,
    val relatedPaperCode: String? = null,
    val questionSetId: String? = null,
    val submissionText: String? = null,
    val taskSteps: List<String>? = null,
    val answerNotes: String? = null,
    val attachmentUrl: String? = null,
    val schoolId: String = "",
    val classId: String = "",
    val scope: String = "GLOBAL",
    val gradeLevel: String? = null,
    val assignedStudentIds: List<String> = emptyList(),
    val gradingMode: String? = null,
    val cbcStrandTag: String? = null,
    val clientSubmissionId: String? = null,
)
