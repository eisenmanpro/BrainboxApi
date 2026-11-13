package com.afrithecus.brainbox.api.identity.web

/**
 * Public signup directory (gaps.md item 1C). The signup form picks a school, then a grade, then a
 * class from server-returned values instead of typing them, so a mismatched grade/class can never be
 * submitted. Only names and ids are published here: no enrolment counts, staff counts or learner data.
 */
data class SignupSchoolPayload(
    val id: String,
    val name: String,
    /** False when the school's ICT admin has closed self-signup. */
    val registrationOpen: Boolean,
)

data class SignupClassPayload(
    val name: String,
    val gradeLevel: String,
)

data class SignupSchoolDetailPayload(
    val id: String,
    val name: String,
    val registrationOpen: Boolean,
    /** Distinct grade levels from the school's class catalogue, in catalogue order. */
    val grades: List<String>,
    val classes: List<SignupClassPayload>,
)
