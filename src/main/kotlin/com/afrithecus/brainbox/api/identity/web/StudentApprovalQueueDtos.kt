package com.afrithecus.brainbox.api.identity.web

/**
 * A learner waiting for (or rejected by) the class teacher's approval, as the roster
 * screen reads it (`GET /auth/students/pending|rejected?teacherCode=`).
 *
 * Shape pinned by the web consumer: `RosterDomain.normalizePendingStudent` reads exactly
 * these fields (and degrades an absent one to null/0 rather than fabricating it).
 */
data class PendingStudentPayload(
    val id: String,
    val name: String,
    val phoneNumber: String = "",
    val admissionNumber: String? = null,
    /** The learner's grade: their own field, else the grade of the class they joined. */
    val grade: String? = null,
    /** The class code the learner used at signup (kept on the account). */
    val referredByTeacherCode: String? = null,
    /** Signup timestamp (epoch millis). */
    val joinedAt: Long = 0L,
)
