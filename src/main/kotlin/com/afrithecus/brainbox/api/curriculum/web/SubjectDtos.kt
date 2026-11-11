package com.afrithecus.brainbox.api.curriculum.web

/** One selectable subject served to the clients (N3). */
data class SubjectPayload(
    val id: String,
    val code: String,
    val name: String,
    /** Grade ranges the subject is offered in; empty means every grade. */
    val gradeBands: List<String> = emptyList(),
)
