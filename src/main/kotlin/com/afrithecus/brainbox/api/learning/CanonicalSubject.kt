package com.afrithecus.brainbox.api.learning

/**
 * The client Subject enum is fixed to eight values, so the wire value must be an
 * enum name; anything else rides customSubjectName with a safe placeholder enum.
 *
 * Shared by the learning hub and the content projection service so generated and
 * authored content canonicalise identically (doc 03 §1.6).
 */
object CanonicalSubject {

    /** Safe placeholder stored when the raw subject is not one of the client enum values. */
    const val DEFAULT = "MATHEMATICS"

    private val SUBJECTS = setOf(
        "MATHEMATICS", "ENGLISH", "KISWAHILI", "PHYSICS",
        "CHEMISTRY", "BIOLOGY", "HISTORY", "GEOGRAPHY",
    )

    private val SUBJECT_ALIASES = mapOf(
        "MATH" to "MATHEMATICS",
        "MATHS" to "MATHEMATICS",
        "MATHEMATIC" to "MATHEMATICS",
        "SWAHILI" to "KISWAHILI",
        "BIO" to "BIOLOGY",
        "CHEM" to "CHEMISTRY",
        "PHYS" to "PHYSICS",
        "HIST" to "HISTORY",
        "GEO" to "GEOGRAPHY",
        "ENG" to "ENGLISH",
    )

    /** The enum name for a recognised subject, or null when it must ride customSubjectName. */
    fun canonical(raw: String): String? {
        val cleaned = raw.trim().uppercase().replace(Regex("\\s+"), "_")
        if (cleaned in SUBJECTS) return cleaned
        return SUBJECT_ALIASES[cleaned] ?: SUBJECT_ALIASES[cleaned.replace("_", "")]
    }

    /** The value to store in the subject column: the canonical enum name or the placeholder. */
    fun required(raw: String): String = canonical(raw) ?: DEFAULT

    /** The value for customSubjectName when the subject is not a client enum, else null. */
    fun custom(raw: String): String? =
        if (canonical(raw) == null) raw.trim().takeIf { it.isNotEmpty() } else null
}
