package com.afrithecus.brainbox.api.classes.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

// ---------------------------------------------------------------------------
// Classes & roster payloads (doc 04 §2.2 + homework prerequisites).
// ---------------------------------------------------------------------------

data class CreateClassRequest(
    @field:NotBlank
    val name: String,
    @field:NotBlank
    val grade: String,
    @field:NotBlank
    val subject: String,
)

data class TeacherClassPayload(
    val classId: String,
    val name: String,
    val grade: String,
    val subject: String,
    val studentCount: Int,
)

data class AddStudentsRequest(
    @field:NotEmpty
    val studentIds: List<String>,
)

data class StudentInClassPayload(
    val studentId: String,
    val name: String,
    val studentAdmissionNumber: String? = null,
)
