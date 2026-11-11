package com.afrithecus.brainbox.api.teacher.model

/** What a teacher asked to change (N3). */
enum class TeacherChangeKind {
    /** Primary class + grades taught, chosen from the school's class catalogue. */
    CLASS_ASSIGNMENT,
    /** Move to a different school; the destination school's admin decides. */
    SCHOOL,
    /** Subjects taught, chosen from the BrainBox subject catalogue. */
    SUBJECTS,
}

/** Lifecycle of a teacher change request. Only a school admin moves it out of PENDING. */
enum class TeacherChangeStatus { PENDING, APPROVED, REJECTED }
