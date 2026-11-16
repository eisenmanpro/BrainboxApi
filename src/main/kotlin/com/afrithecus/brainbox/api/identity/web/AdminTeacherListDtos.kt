package com.afrithecus.brainbox.api.identity.web

/**
 * Teacher list payloads for the ICT admin's user-management tabs
 * (`GET /admin/schools/{schoolId}/{pending,rejected,managed}-teachers`).
 *
 * Shapes are pinned by the web consumer: `AdminApprovalsRepository` reads them through
 * `AdminDomain.normalizePendingTeacher` / `normalizeManagedTeacher`, so every field the
 * normalisers read is present here (and unknown/absent scalars degrade to safe defaults
 * client-side rather than fabricating data).
 *
 * The derived fields come from the teacher's own approved catalogue when it exists
 * (`teacher_settings.primary_class_name` / `grades_taught` / `subjects_taught`) and fall
 * back to the classes they actually own, exactly like the teacher profile read does (N3).
 */
data class PendingTeacherPayload(
    val id: String,
    val name: String,
    val phoneNumber: String = "",
    /** The class teacher code the teacher shares with learners, when one is issued. */
    val teacherCode: String? = null,
    val className: String? = null,
    val gradeLevels: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val tscNumber: String? = null,
    /** Signup timestamp (epoch millis) so the queue can be shown oldest-first. */
    val joinedAt: Long = 0L,
)

/**
 * A learner row for the admin audience picker (school announcements targeting, findings
 * "ADMIN ANNOUNCEMENTS 1"): exactly what a picker needs to filter by grade/class and to
 * address one learner — plus the guardian contact F9 keeps on the same account.
 */
data class AdminStudentPayload(
    val id: String,
    val name: String,
    val gradeLevel: String? = null,
    val classIds: List<String> = emptyList(),
    val classNames: List<String> = emptyList(),
    /** Guardian/learner contact: the linked parent's name, else the recorded guardian name. */
    val parentName: String? = null,
    val parentPhone: String? = null,
)

/** A teacher the school manages: an approved (VERIFIED) or frozen (FROZEN) account. */
data class ManagedTeacherPayload(
    val id: String,
    val name: String,
    val phoneNumber: String = "",
    /** The account role name ("TEACHER"); the web renders a role label from it. */
    val role: String = "TEACHER",
    /** TEACHER, CTEACHER, GRADE_COORDINATOR or ICT_ADMIN. */
    val subRole: String? = null,
    val className: String? = null,
    val gradeLevels: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val tscNumber: String? = null,
    val isFrozen: Boolean = false,
    /** VERIFIED or FROZEN (the two states this list contains). */
    val verificationStatus: String = "VERIFIED",
)
