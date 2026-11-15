package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.identity.SchoolConfigService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.media.MediaService
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** School branding configuration edited by ICT admins. */
@RestController
@RequestMapping("/admin/schools")
@PreAuthorize("hasAnyRole('ADMIN','ICT_ADMIN')")
class SchoolConfigController(
    private val service: SchoolConfigService,
    private val mediaService: MediaService,
) {

    @GetMapping("/{schoolId}/config")
    fun get(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): SchoolConfigPayload = service.get(current, schoolId)

    @PutMapping("/{schoolId}/config")
    fun update(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @RequestBody request: SchoolConfigPayload,
    ): SchoolConfigPayload = service.update(current, schoolId, request)

    /**
     * Stores an uploaded logo/stamp (multipart `file`) and returns its public URL. The
     * client then saves that URL through the existing config PUT, so a cancelled form
     * never changes the branding. Images are image-only (PNG/JPEG/GIF, 2 MB) and stored
     * as opaque object keys; the filename is never client-chosen.
     */
    @PostMapping("/{schoolId}/config/branding/{kind}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadBranding(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable kind: String,
        @RequestPart("file") file: MultipartFile,
    ): SchoolBrandingUploadPayload {
        val slot = kind.trim().lowercase().takeIf { it == "logo" || it == "stamp" }
            ?: throw invalidArgument("kind must be logo or stamp")
        service.requireBrandingUploadAccess(current, schoolId)
        val stored = mediaService.storeBrandingImage(file)
        return SchoolBrandingUploadPayload(kind = slot, url = stored.url, mediaType = stored.mediaType)
    }
}
