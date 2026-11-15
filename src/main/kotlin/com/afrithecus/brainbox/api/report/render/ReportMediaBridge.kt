package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.media.MediaService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component

/**
 * Installs the report renderer's media resolver (D16/A23 follow-up). Branding images are
 * uploaded through the media service and stored as opaque object keys served at
 * `/media/{key}`; the PDF renderer reads that key straight from the object store instead
 * of making an outbound HTTP request, which the report SSRF policy would refuse for a
 * loopback/local deployment origin.
 */
@Component
class ReportMediaBridge(private val mediaService: MediaService) {

    @PostConstruct
    fun install() {
        ReportImages.mediaResolver = mediaService::loadByMediaUrl
    }

    @PreDestroy
    fun clear() {
        ReportImages.mediaResolver = null
    }
}
