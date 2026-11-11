package com.afrithecus.brainbox.api.auth

/**
 * F9/N6: one canonical Kenyan phone format for stored accounts ("0712345678"). Signup accepts
 * +254/254/07/01 forms and stores the canonical one, so a learner can sign in with either form
 * and the unique-phone index cannot be defeated by formatting. Login tries the raw value first
 * (legacy rows) and then the canonical form.
 */
object PhoneNumbers {

    private val KENYAN = Regex("^0[17][0-9]{8}$")

    fun normalize(raw: String): String? {
        val digits = raw.trim().filter { it.isDigit() }
        val local = when {
            digits.length == 12 && digits.startsWith("254") -> "0" + digits.substring(3)
            digits.length == 10 && digits.startsWith("0") -> digits
            digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "0" + digits
            else -> return null
        }
        return local.takeIf { KENYAN.matches(it) }
    }
}
