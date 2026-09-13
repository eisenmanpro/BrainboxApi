package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.identity.web.SchoolConfigPayload

/**
 * Branding applied to a server-rendered PDF. Mirrors the client's
 * PdfGenerator.SchoolReportBranding (core/utils/PdfGenerator.kt) so a report
 * generated on-device and one generated server-side look the same.
 */
data class ReportBranding(
    val schoolName: String,
    val motto: String? = null,
    val logoUrl: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val primaryColor: String? = null,
    val watermarkText: String? = null,
) {
    companion object {
        fun from(config: SchoolConfigPayload): ReportBranding = ReportBranding(
            schoolName = config.schoolName,
            motto = config.motto,
            logoUrl = config.logoUrl,
            address = config.address,
            phone = config.phone,
            email = config.email,
            primaryColor = config.primaryColor,
            watermarkText = config.watermarkText,
        )
    }
}
