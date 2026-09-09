package com.afrithecus.brainbox.api.security

import java.security.MessageDigest

/** SHA-256 hex digest used to store refresh tokens (never store the raw token). */
object TokenHash {

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
