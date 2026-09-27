package com.afrithecus.brainbox.api.media

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * Builds the scanner each named provider uses, so the *effective* provider is chosen per
 * scan from the runtime settings rather than frozen at startup. Switching ClamAV on or off
 * (or moving to an HTTP sidecar) is therefore a config/console change, not a redeploy.
 */
@Configuration
class MediaScannerConfig {

    @Bean
    fun mediaScannerRegistry(properties: MediaProperties, mapper: ObjectMapper): MediaScannerRegistry =
        MediaScannerRegistry(
            mapOf(
                "none" to NoOpMediaScanner(),
                "clamav" to ClamAvMediaScanner(properties),
                "http" to HttpMediaScanner(properties, mapper),
            ),
        )
}

/**
 * The named scanners. An unknown name resolves to the no-op scanner and is reported as
 * such, so a bad console write degrades to "unscanned and recorded" instead of failing
 * every upload.
 */
class MediaScannerRegistry(private val scanners: Map<String, MediaScanner>) {

    fun forName(name: String?): MediaScanner {
        val normalized = name?.trim()?.lowercase().orEmpty()
        return scanners[normalized] ?: scanners.getValue("none")
    }

    fun names(): Set<String> = scanners.keys
}
