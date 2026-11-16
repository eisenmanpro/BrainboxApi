package com.afrithecus.brainbox.api.identity.web

/**
 * Wire contract for the ICT admin's learner management surface (findings
 * "user management": a school admin has no way to see, filter, export or
 * transfer the school's learners). Every payload is school-scoped; ICT admins
 * see only their own school and a platform ADMIN any school (enforced in the
 * service, never from a request body).
 */
data class LearnerPagePayload(
    val items: List<LearnerSummaryPayload>,
    /** 0-based page index. */
    val page: Int,
    val size: Int,
    val total: Int,
    val totalPages: Int,
    val counts: LearnerCountsPayload,
    val filters: LearnerFilterOptionsPayload,
)

/** Counts over everything the non-status filters select, so the tab header stays honest. */
data class LearnerCountsPayload(
    val total: Int,
    val active: Int,
    val inactive: Int,
)

/**
 * Filter options the learner tab needs in one round trip: the grades and classes
 * that actually exist in this school plus the status values the server accepts.
 */
data class LearnerFilterOptionsPayload(
    val grades: List<String> = emptyList(),
    val classes: List<LearnerClassOptionPayload> = emptyList(),
    val statuses: List<String> = emptyList(),
)

data class LearnerClassOptionPayload(
    val classId: String,
    val name: String,
    val gradeLevel: String,
)

data class LearnerSummaryPayload(
    val studentId: String,
    val name: String,
    val admissionNumber: String? = null,
    val gradeLevel: String? = null,
    val classIds: List<String> = emptyList(),
    val classNames: List<String> = emptyList(),
    val guardianName: String? = null,
    val guardianPhone: String? = null,
    val phoneNumber: String? = null,
    /** AccountStatus name (VERIFIED / PENDING_VERIFICATION / REJECTED / FROZEN). */
    val status: String,
    val isActive: Boolean,
    val accountKind: String,
)

data class LearnerDetailPayload(
    val studentId: String,
    val name: String,
    val admissionNumber: String? = null,
    val gradeLevel: String? = null,
    val classIds: List<String> = emptyList(),
    val classNames: List<String> = emptyList(),
    val guardianName: String? = null,
    val guardianPhone: String? = null,
    /** Legacy guardian account row, when the learner was linked to one. */
    val parentUserId: String? = null,
    val parentName: String? = null,
    val parentPhone: String? = null,
    val phoneNumber: String? = null,
    val email: String? = null,
    val status: String,
    val isActive: Boolean,
    val accountKind: String,
    val joinedAt: Long,
    val lastLoginAt: Long? = null,
)

/**
 * Exactly one destination must be set: [toClassId] moves the learner inside this
 * school (reusing the audited class-transition pipeline); [targetSchoolId]
 * transfers them to another school and is a platform-admin action.
 */
data class LearnerTransferRequest(
    val toClassId: String? = null,
    val targetSchoolId: String? = null,
    val reason: String? = null,
)

data class LearnerTransferResultPayload(
    val studentId: String,
    /** CLASS (a move inside this school) or SCHOOL (a cross-school transfer). */
    val kind: String,
    val fromSchoolId: String,
    val toSchoolId: String? = null,
    val fromClassIds: List<String> = emptyList(),
    val toClassId: String? = null,
    val message: String,
)
