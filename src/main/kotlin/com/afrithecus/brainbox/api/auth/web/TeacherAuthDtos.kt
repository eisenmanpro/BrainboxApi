package com.afrithecus.brainbox.api.auth.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Teacher auth lifecycle payloads (docs/ongoing/api_teacher_roster_changes.md).
 * Field names match the Android AuthApi/models; the actor is always the bearer
 * token, so no request carries a trusted identity.
 */
data class TeacherSignupRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val phoneNumber: String,
    @field:NotBlank val password: String,
    /**
     * The address is confirmed by an emailed code before the account can be used
     * (gaps.md item 3). Required for every staff signup.
     */
    @field:NotBlank @field:Size(max = 255) val email: String? = null,
    /** Picked from the directory, never typed: the school's grades/classes come from its config. */
    val schoolId: String? = null,
    /** TEACHER (omitted) | CTEACHER | GRADE_COORDINATOR | ICT_ADMIN. */
    val subRole: String? = null,
    /** Class teacher: exactly one grade. Teacher/coordinator: one or more grades. */
    val grades: List<String> = emptyList(),
    /** Class teacher only; must be one of the school's configured classes. */
    val className: String = "",
    val subjects: List<String>? = null,
    val tscNumber: String? = null,
    val stayLoggedIn: Boolean = true,
)

data class TeacherSignupResponse(
    val success: Boolean,
    val message: String,
    /** Null until the emailed code is confirmed: an unverified account gets no session. */
    val sessionToken: String? = null,
    val user: UserPayload,
    val ctc: String,
    val ctcShareText: String,
    val isNewUser: Boolean = false,
    /** True when the client must show the code screen before the account can sign in. */
    val emailVerificationRequired: Boolean = false,
    /** Masked address for the code screen ("e***@gmail.com"). */
    val maskedEmail: String? = null,
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
