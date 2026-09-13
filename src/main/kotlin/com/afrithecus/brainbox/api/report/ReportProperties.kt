package com.afrithecus.brainbox.api.report

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Reporting-hub settings (docs/ongoing/api_reports_changes.md): where rendered
 * PDFs are stored, how long signed download links live, the server-authoritative
 * weekly export quota and the server-side sync/async decision thresholds.
 */
@ConfigurationProperties(prefix = "app.reports")
data class ReportProperties(
    /** Local disk root for rendered report PDFs (replaced by object storage later). */
    val storageDir: String = "./data/reports",
    /** Whether to embed the school logo in rendered PDFs (disabled in tests). */
    val embedLogo: Boolean = true,
    /** HMAC key for short-lived download links (override per environment). */
    val downloadSecret: String = "dev-only-report-download-secret-change-me",
    /** Lifetime of a signed download URL. */
    val downloadTtl: Duration = Duration.ofMinutes(30),
    /** Exports allowed per account per calendar week. */
    val weeklyQuota: Int = 3,
    /** Grade-wide tables larger than this many students are rendered asynchronously. */
    val syncThresholdRows: Int = 300,
    /** Student reports for more than this many students are rendered asynchronously. */
    val syncThresholdStudents: Int = 5,
    /** Poll hint returned on job payloads. */
    val pollHintMillis: Long = 2000,
    /** Days a rendered report file is retained before pruning. */
    val retentionDays: Int = 90,
)
