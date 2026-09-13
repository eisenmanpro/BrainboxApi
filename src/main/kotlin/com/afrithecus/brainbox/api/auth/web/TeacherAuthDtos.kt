package com.afrithecus.brainbox.api.auth.web

import jakarta.validation.constraints.NotBlank

/**
 * Teacher auth lifecycle payloads (docs/ongoing/api_teacher_roster_changes.md).
 * Field names match the Android AuthApi/models; the actor is always the bearer
 * token, so no request carries a trusted identity.
 */
data class TeacherSignupRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val phoneNumber: String,
    @field:NotBlank val password: String,
    val schoolId: String? = null,
    val schoolName: String = "",
    val schoolListed: Boolean = true,
    val grades: List<String> = emptyList(),
    val className: String = "",
    val studentCount: Int = 0,
    val subjects: List<String>? = null,
    val tscNumber: String? = null,
    val bio: String? = null,
    val stayLoggedIn: Boolean = true,
)

data class TeacherSignupResponse(
    val success: Boolean,
    val message: String,
    val sessionToken: String? = null,
    val user: UserPayload,
    val ctc: String,
    val ctcShareText: String,
    val isNewUser: Boolean = false,
)

data class ValidateCtcRequest(
    @field:NotBlank val ctc: String,
    val schoolId: String? = null,
    val forStudent: Boolean = false,
)

enum class CtcType { TEACHER, ADMIN }

data class CtcValidationPayload(
    val isValid: Boolean,
    val teacherName: String? = null,
    val message: String? = null,
    val ctcType: CtcType? = null,
    val teacherCode: String? = null,
    val schoolId: String? = null,
    val schoolName: String? = null,
    val grade: String? = null,
    val className: String? = null,
)

data class RotateCtcResponsePayload(
    val success: Boolean,
    val message: String,
    val teacherCode: String? = null,
    val user: UserPayload? = null,
)

data class TeacherTransferRequest(
    val targetSchoolId: String = "",
    val targetSchoolName: String = "",
)

data class TeacherInfoUpdateRequest(
    val name: String? = null,
    val phoneNumber: String? = null,
    val className: String? = null,
    val subjects: List<String>? = null,
    val tscNumber: String? = null,
    val gradeLevels: List<String>? = null,
)

data class PhoneLoginRequest(
    @field:NotBlank val phoneNumber: String,
    @field:NotBlank val password: String,
)
