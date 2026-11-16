package com.afrithecus.brainbox.api.classes.web

/**
 * Admin class management for the School Config redesign (findings "ADMIN SCHOOL CONFIG 3").
 *
 * **Data model:** `teacher_classes` rows are authoritative for class instances (teacher
 * ownership, learner memberships, exam class tags); `school_configs.classes` is only the
 * signup picker's snapshot and is re-synced after every write here.
 */
data class AdminClassPayload(
    val id: String,
    val name: String,
    val gradeLevel: String,
    val stream: String? = null,
    /** The class teacher's account id, null when the class is unassigned. */
    val teacherUserId: String? = null,
    val teacherName: String? = null,
    /** Free-text subject the class is taught under (teacher_classes.subject). */
    val subject: String = "General",
    val studentCount: Int = 0,
    val isActive: Boolean = true,
)

/** Create a class in the school's catalogue: subject is optional and defaults to "General". */
data class CreateAdminClassRequest(
    val name: String,
    val gradeLevel: String,
    val stream: String? = null,
    val subject: String? = null,
    /** Optional class teacher; must be an active teacher of the same school. */
    val teacherUserId: String? = null,
)

/** Partial update; an absent field leaves the stored value untouched. */
data class UpdateAdminClassRequest(
    val name: String? = null,
    val gradeLevel: String? = null,
    val stream: String? = null,
    val subject: String? = null,
    val teacherUserId: String? = null,
)

/** Reassign the class teacher (the previous teacher loses the class, keeping their account). */
data class AssignClassTeacherRequest(
    val teacherId: String,
)
