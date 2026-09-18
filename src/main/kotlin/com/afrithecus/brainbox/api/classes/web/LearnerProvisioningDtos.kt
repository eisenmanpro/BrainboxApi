package com.afrithecus.brainbox.api.classes.web

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

// ---------------------------------------------------------------------------
// Roster-only learner provisioning. A class teacher (or a grade coordinator /
// ICT admin for the same school) creates a real student record for a pupil
// without a smartphone, so the traditional exam engine can enter marks, rank,
// and print reports for them. The record is login-disabled and can be upgraded
// to full app access later.
// ---------------------------------------------------------------------------

/** One learner to provision. Admission number and guardian details are optional. */
data class ProvisionLearnerRequest(
    @field:NotBlank
    @field:Size(max = 120)
    val name: String,
    @field:Size(max = 64)
    val admissionNumber: String? = null,
    @field:Size(max = 160)
    val guardianName: String? = null,
    @field:Size(max = 32)
    val guardianPhone: String? = null,
)

/** Paste/import a whole class. Re-running the same list is idempotent. */
data class BulkProvisionLearnersRequest(
    @field:NotEmpty
    val learners: List<@Valid ProvisionLearnerRequest>,
)

data class ProvisionedLearnerPayload(
    val id: String,
    val name: String,
    val admissionNumber: String? = null,
    val grade: String,
    val classId: String,
    /** FULL or ROSTER_ONLY; a roster-only learner has no login. */
    val accountKind: String,
    val guardianName: String? = null,
    val guardianPhone: String? = null,
    /** False when the learner already existed and the call was a no-op update. */
    val created: Boolean = true,
)

/** A row the bulk call could not provision, with the reason, so nothing is silent. */
data class ProvisionLearnerError(
    val index: Int,
    val name: String,
    val message: String,
)

data class BulkProvisionLearnersResult(
    val created: Int,
    val existing: Int,
    val learners: List<ProvisionedLearnerPayload>,
    val errors: List<ProvisionLearnerError> = emptyList(),
)

/**
 * Teacher-initiated upgrade of a roster-only record to a real account. The phone
 * is mandatory; a password is optional, and when omitted the server generates a
 * one-time password and returns it once so the teacher can hand it over.
 */
data class EnableAppAccessRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "invalid phone number")
    val phoneNumber: String,
    @field:Size(min = 8, max = 128)
    val password: String? = null,
)

data class EnableAppAccessResult(
    val userId: String,
    val phoneNumber: String,
    val accountKind: String,
    /** Present exactly once, only when the server generated the password. */
    val temporaryPassword: String? = null,
)
