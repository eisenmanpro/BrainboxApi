package com.afrithecus.brainbox.api.teacher.web

import com.afrithecus.brainbox.api.curriculum.web.SubjectPayload
import com.afrithecus.brainbox.api.identity.web.SchoolClassPayload

/** Everything the teacher settings screen needs to render its drop-downs (N3). */
data class TeacherSchoolConfigPayload(
    val schoolId: String,
    val schoolName: String,
    val classes: List<SchoolClassPayload> = emptyList(),
    val subjects: List<SubjectPayload> = emptyList(),
)

/**
 * A teacher's request to change a governed profile field. Only the field that
 * belongs to [kind] is read; the others are ignored. The server validates the
 * proposed value against the school config / subject catalogue before storing it.
 */
data class CreateTeacherChangeRequest(
    /** CLASS_ASSIGNMENT | SCHOOL | SUBJECTS */
    val kind: String = "",
    val className: String? = null,
    val gradeLevels: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val schoolId: String? = null,
)

/** An admin's decision on a request; the note is optional for approve, useful for reject. */
data class TeacherChangeDecisionRequest(
    val note: String? = null,
)

/**
 * One request as both sides see it. Current/proposed values are flattened so the
 * approval gate can show them side by side without the client parsing JSON, and
 * [origin] states whether the teacher is moving within this school or arriving
 * from another one (the owner's provenance requirement).
 */
data class TeacherChangeRequestPayload(
    val id: String,
    val teacherId: String,
    val teacherName: String,
    /** CLASS_ASSIGNMENT | SCHOOL | SUBJECTS */
    val kind: String,
    /** PENDING | APPROVED | REJECTED */
    val status: String,
    val currentClassName: String? = null,
    val currentGradeLevels: List<String> = emptyList(),
    val currentSubjects: List<String> = emptyList(),
    val proposedClassName: String? = null,
    val proposedGradeLevels: List<String> = emptyList(),
    val proposedSubjects: List<String> = emptyList(),
    /** Where the teacher is today (null when the account has no school). */
    val currentSchoolId: String? = null,
    val currentSchoolName: String? = null,
    /** The school whose admin decides; for a transfer this is the destination. */
    val targetSchoolId: String? = null,
    val targetSchoolName: String? = null,
    /** WITHIN_SCHOOL | FROM_OTHER_SCHOOL */
    val origin: String,
    /** True only for the deciding school's admin while the request is pending. */
    val canDecide: Boolean,
    val createdAt: Long,
    val decidedAt: Long? = null,
    val reviewerName: String? = null,
    val reviewNote: String? = null,
)
