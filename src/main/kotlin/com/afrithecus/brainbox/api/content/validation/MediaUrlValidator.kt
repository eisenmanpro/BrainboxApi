package com.afrithecus.brainbox.api.content.validation

import com.afrithecus.brainbox.api.content.figure.FigureSpecs
import com.afrithecus.brainbox.api.media.UrlReputationService
import com.afrithecus.brainbox.api.media.UrlReputationStatus
import com.afrithecus.brainbox.api.media.security.MediaAlertKind
import com.afrithecus.brainbox.api.media.security.MediaSecurityService
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * URL reputation for every externally hosted URL a generated unit carries — today the
 * `IMAGE` figure URLs, which a learner's device fetches directly.
 *
 * This is the content-pipeline half of the URL-reputation scope: the scanner protects
 * uploaded bytes, and this protects the URLs the generator puts in front of a learner.
 * It is off unless the deployment or the console enables it (`media_url_reputation_*`), in
 * which case a blocked URL is a BLOCKER (the supervisor loop revises on it) and a provider
 * failure is a WARNING by default — a reputation outage must not stop content production,
 * but it must be visible and alerted.
 *
 * The deterministic SSRF rules in [FigureSpecs] still apply on their own; this validator
 * adds the reputation verdict on top.
 */
@Component
class MediaUrlValidator(
    private val urlReputation: UrlReputationService,
    private val security: MediaSecurityService,
    private val mapper: ObjectMapper,
) : ContentValidator {

    override val name: String = "media_url"

    override fun validate(ctx: ValidationContext): List<ValidationFinding> {
        if (!urlReputation.enabled()) return emptyList()
        val findings = mutableListOf<ValidationFinding>()
        urlsOf(ctx).forEach { url ->
            val result = urlReputation.enforce(url)
            when (result.status) {
                UrlReputationStatus.CLEAN, UrlReputationStatus.SKIPPED -> Unit
                UrlReputationStatus.BLOCKED -> {
                    findings += ValidationFinding(
                        FindingSeverity.BLOCKER,
                        "MEDIA_URL_BLOCKED",
                        "the image URL was refused: " + (result.detail ?: url),
                    )
                    security.alert(
                        kind = MediaAlertKind.URL_BLOCKED,
                        detail = "Generated image URL refused: " + (result.detail ?: "flagged"),
                        url = url,
                        provider = result.provider,
                    )
                }
                UrlReputationStatus.ERROR -> {
                    findings += ValidationFinding(
                        FindingSeverity.WARNING,
                        "MEDIA_URL_UNCHECKED",
                        "the image URL could not be checked: " + (result.detail ?: url),
                    )
                    security.alert(
                        kind = MediaAlertKind.URL_ERROR,
                        detail = "Image URL could not be checked: " + (result.detail ?: "provider unavailable"),
                        url = url,
                        provider = result.provider,
                    )
                }
            }
        }
        return findings
    }

    /** Every distinct external URL the unit carries in a figure spec. */
    private fun urlsOf(ctx: ValidationContext): List<String> {
        val specs = mutableListOf<String?>()
        specs += ctx.steps.map { it.figureSpec }
        specs += ctx.questions.map { it.figureSpec }
        return specs
            .filterNotNull()
            .mapNotNull { json -> runCatching { mapper.readTree(json) }.getOrNull() }
            .mapNotNull { node -> FigureSpecs.imageUrl(node) }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
