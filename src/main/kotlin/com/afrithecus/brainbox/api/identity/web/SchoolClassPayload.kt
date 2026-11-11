package com.afrithecus.brainbox.api.identity.web

/**
 * One class in a school's configuration (N3). The ICT admin maintains the list
 * in the school config screen; teacher settings use it for every class/grade
 * drop-down so a teacher never types a class name.
 */
data class SchoolClassPayload(
    val name: String,
    val gradeLevel: String,
    val stream: String? = null,
)
