package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.model.Role

/**
 * F9/N6: the learner credential is a 4-digit PIN, because it must be memorable for a child;
 * staff keep a password (8-128).
 *
 * Compatibility rule: the PIN shape is enforced when the signup names the kind (`PIN`), and
 * always for change/reset on an account already stored as PIN. Legacy learner accounts that
 * signed up with a password keep working — login never validates the shape, only compares it.
 */
object CredentialRules {

    const val KIND_PIN = "PIN"
    const val KIND_PASSWORD = "PASSWORD"

    private val PIN = Regex("^[0-9]{4}$")
    private val TRIVIAL = buildSet {
        for (digit in 0..9) add("" + digit + digit + digit + digit)
        add("1234"); add("4321")
        for (start in 0..5) {
            add("" + start + (start + 1) + (start + 2) + (start + 3))
            add("" + (start + 3) + (start + 2) + (start + 1) + start)
        }
    }

    /**
     * The validation message for a credential of [kind], or null when it is acceptable.
     */
    fun validate(kind: String, raw: String): String? = when (kind) {
        KIND_PIN -> when {
            !PIN.matches(raw) -> "Learners use a 4-digit PIN."
            raw in TRIVIAL -> "Choose a less obvious 4-digit PIN."
            else -> null
        }
        else -> when {
            raw.length < 8 -> "Password must be at least 8 characters."
            raw.length > 128 -> "Password must be at most 128 characters."
            else -> null
        }
    }

    /**
     * Normalises the kind token a client sent. An omitted/unknown token means **legacy
     * PASSWORD**, not the role default: existing learner clients that never sent the field
     * must keep working, and only a client that explicitly says PIN opts into the 4-digit rule.
     */
    fun kindOr(raw: String?, role: Role): String =
        raw?.trim()?.uppercase()?.takeIf { it == KIND_PIN || it == KIND_PASSWORD } ?: KIND_PASSWORD
}
