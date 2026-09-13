package com.afrithecus.brainbox.api.auth.web

import com.afrithecus.brainbox.api.subscription.SubscriptionView
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

// ---------------------------------------------------------------------------
// Request payloads. Field names MUST match the Android JSON contracts
// (docs/backend_contracts/01_AUTH_IDENTITY_AND_ACCESS.md §1).
// ---------------------------------------------------------------------------

data class LoginRequest(
    @field:NotBlank
    val identifier: String,
    @field:NotBlank
    val password: String,
)

data class SignupRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
    @field:NotBlank
    @field:Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "invalid phone number")
    val phoneNumber: String,
    @field:NotBlank
    @field:Size(min = 8, max = 128, message = "password must be 8-128 characters")
    val password: String,
    val schoolId: String? = null,
    val schoolName: String? = null,
    @field:Size(max = 8)
    val teacherCode: String? = null,
    @field:NotBlank
    val role: String,
)

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String,
)

data class SwitchSessionRequest(
    @field:NotBlank
    val targetUserId: String,
    @field:NotBlank
    val targetRole: String,
)

// ---------------------------------------------------------------------------
// Response payloads (doc 01 §1.1 login/§1.3 me/§1.5 refresh, §2.1 user model).
// ---------------------------------------------------------------------------

data class UserPayload(
    val id: String,
    val phoneNumber: String?,
    val name: String,
    val role: String,
    val subRole: String?,
    val schoolId: String?,
    val schoolName: String?,
    val studentAdmissionNumber: String?,
    val parentId: String?,
    val childId: String? = null,
    val referredByTeacherCode: String?,
    val joinedTeacherId: String?,
    val gradeLevel: String?,
    val isActive: Boolean,
    val isVerified: Boolean,
    val createdAt: Long,
    val lastLogin: Long?,
    // Teacher account view (docs/ongoing/api_teacher_roster_changes.md). Omitted
    // (null) for non-teachers; the client User model reads all of these.
    val teacherCode: String? = null,
    val teacherSubRole: String? = null,
    val gradesTaught: List<String>? = null,
    val className: String? = null,
    val studentCount: Int? = null,
    val subjects: List<String>? = null,
    val tscNumber: String? = null,
    val gradeAssignments: List<String>? = null,
    val gradeLevelAssignments: List<String>? = null,
    val onboardingCompleted: Boolean = false,
    val managedSchoolId: String? = null,
    val verificationStatus: String = "VERIFIED",
    val ctcFrozen: Boolean = false,
)

data class SubscriptionPayload(
    val userId: String,
    val status: String,
    val tier: String,
    val expiryDate: Long?,
    val totalPaid: Int,
)

/** Login/signup/me response (doc 01 §1.1). */
data class AuthResponse(
    val success: Boolean,
    val message: String,
    val sessionToken: String? = null,
    val refreshToken: String? = null,
    val user: UserPayload,
    val subscription: SubscriptionPayload,
    val accessLevel: String,
    val linkedChildren: List<UserPayload>? = null,
)

/** Refresh response (doc 01 §1.5). */
data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

fun SubscriptionView.toPayload(): SubscriptionPayload = SubscriptionPayload(
    userId = userId.toString(),
    status = status,
    tier = tier,
    expiryDate = expiryDate,
    totalPaid = totalPaid,
)
