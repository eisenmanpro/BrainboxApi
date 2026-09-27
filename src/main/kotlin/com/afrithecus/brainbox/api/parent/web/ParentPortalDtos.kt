package com.afrithecus.brainbox.api.parent.web

/**
 * The Android `ChildInfo` shape for a parent's linked learner.
 *
 * `avatarUrl` is null for a learner with no photo: the app renders the name's initial.
 */
data class LinkedChildPayload(
    val id: String,
    val name: String,
    val grade: String,
    val avatarUrl: String? = null,
)

/**
 * Links a child to the calling parent. `admissionNumber` is the CTC the school issues to
 * the learner (it is unique per school and printed on reports and the class roster), so a
 * guardian never has to know an internal id.
 */
data class LinkChildRequest(
    val admissionNumber: String,
)
