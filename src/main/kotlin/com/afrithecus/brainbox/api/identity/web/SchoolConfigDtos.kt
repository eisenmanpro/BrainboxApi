package com.afrithecus.brainbox.api.identity.web

/**
 * Branding/academic configuration for a school (client models.analytics
 * SchoolConfig in repository/admin/SchoolAnalyticsRepository.kt).
 */
data class SchoolConfigPayload(
    val schoolId: String,
    val schoolName: String,
    val motto: String? = null,
    val logoUrl: String? = null,
    val primaryColor: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val watermarkText: String? = null,
    val academicCalendar: List<String> = emptyList(),
    val cbcStrands: List<String> = emptyList(),
)
