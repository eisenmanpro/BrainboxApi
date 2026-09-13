package com.afrithecus.brainbox.api.identity.model

/**
 * Account verification state machine mirrored by the client's AccountStatus
 * (docs/ongoing/api_teacher_roster_changes.md). PENDING_VERIFICATION is the
 * post-signup state awaiting a class teacher / ICT admin decision; REJECTED and
 * FROZEN revoke access without deleting the account.
 */
enum class AccountStatus { PENDING_VERIFICATION, VERIFIED, REJECTED, FROZEN }
