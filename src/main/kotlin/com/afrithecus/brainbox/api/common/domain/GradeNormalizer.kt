package com.afrithecus.brainbox.api.common.domain

/**
 * Canonical grade comparison (server obligation: doc 01 §A, doc 11 Appendix C).
 * "Form 3", "FORM_THREE" and "Grade 08" must all be treated as the same grade.
 *
 * Canonical value: CBC grades stay numeric (Grade 08 -> 8); Form levels are
 * mapped onto the CBC scale (Form N -> N + 5, so Form 3 -> Grade 8), matching
 * the equivalence the client uses ("Form 3" == "Grade 08").
 */
object GradeNormalizer {

    private val WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )

    /**
     * @return canonical integer for the grade, or null when the input does not
     * identify a grade level (e.g. "College", "Adult").
     */
    fun canonicalKey(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.lowercase().replace('_', ' ').replace('-', ' ')
        val isForm = cleaned.contains("form") || cleaned.contains("secondary")
        val number = extractNumber(cleaned) ?: return null
        return if (isForm) number + FORM_OFFSET else number
    }

    fun sameGrade(a: String?, b: String?): Boolean {
        val ka = canonicalKey(a) ?: return false
        val kb = canonicalKey(b) ?: return false
        return ka == kb
    }

    private fun extractNumber(cleaned: String): Int? {
        Regex("""\d{1,2}""").findAll(cleaned).forEach { match ->
            val value = match.value.toIntOrNull()
            if (value != null && value in 1..12) return value
        }
        val tokens = cleaned.split(Regex("""[^a-z0-9]+"""))
        return WORDS.entries.firstOrNull { (word, _) -> word in tokens }?.value
    }

    private const val FORM_OFFSET = 5
}
