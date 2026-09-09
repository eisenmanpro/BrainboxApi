package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.auth.web.UserPayload
import jakarta.validation.constraints.NotBlank

// ---------------------------------------------------------------------------
// Admin identity payloads (doc 01 §2.2/§6.1/§7.2/§8.2/§9).
// ---------------------------------------------------------------------------

data class AdminUserListResponse(
    val users: List<UserPayload>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
)

data class UpdateUserRequest(
    val name: String? = null,
    val gradeLevel: String? = null,
    val isActive: Boolean? = null,
)

data class SubscriptionUpdateRequest(
    @field:NotBlank
    val tier: String,
    val expiryDate: Long? = null,
    val totalPaid: Int? = null,
)

data class ParentLinkRequest(
    @field:NotBlank
    val parentId: String,
    @field:NotBlank
    val childId: String,
)

data class CreateTeacherRequest(
    @field:NotBlank
    val name: String,
    @field:NotBlank
    val email: String,
    @field:NotBlank
    val phoneNumber: String,
    val subject: String? = null,
)

data class TeacherPayload(
    val id: String,
    val name: String,
    val email: String?,
    val phoneNumber: String?,
    val subject: String?,
    val teacherCode: String?,
    val schoolId: String?,
    val isActive: Boolean,
)

data class SchoolPayload(
    val id: String,
    val name: String,
    val county: String?,
    val location: String?,
    val logoUrl: String?,
    val rating: Float,
    val reviews: Int,
    val placementRate: Int,
    val rank: Int,
    val studentCount: Int,
    val teacherCount: Int,
    val adminContact: Any? = null,
    val createdAt: Long,
)

data class UpdateSchoolRequest(
    val name: String? = null,
    val county: String? = null,
    val location: String? = null,
)

data class ResetPasswordResponse(
    val password: String,
)
