package com.afrithecus.brainbox.api.identity.web

/**
 * One grade a teacher can pick during onboarding (N3-03). Shape matches the
 * Android SchoolGradeConfigEntity one for one; the server is the source so a
 * fresh install is not limited to an empty device cache.
 */
data class OnboardingGradeConfigPayload(
    val configId: String,
    val schoolId: String,
    val gradeLevel: String,
    val displayName: String,
    val classTeacherId: String? = null,
    val coordinatorId: String? = null,
    val active: Boolean = true,
    val createdAt: Long,
    val subjects: List<String> = emptyList(),
)
