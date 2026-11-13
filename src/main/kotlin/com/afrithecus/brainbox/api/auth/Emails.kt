package com.afrithecus.brainbox.api.auth

/**
 * Canonical email handling for signup, login and recovery (gaps.md item 3). Addresses are stored
 * lower-cased and trimmed so two spellings of the same inbox cannot create two accounts, and the
 * verification key is case-insensitive.
 */
object Emails {

    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private const val MAX_LENGTH = 255

    /** The canonical address, or null when the input is not a usable email. */
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value.isEmpty() || value.length > MAX_LENGTH || !EMAIL.matches(value)) return null
        return value
    }

    /** "eisenman7@gmail.com" -> "e***@gmail.com": enough to recognise the inbox, not to replay it. */
    fun mask(email: String): String {
        val at = email.indexOf('@')
        if (at <= 0) return "***"
        return email.take(1) + "***" + email.substring(at)
    }
}
