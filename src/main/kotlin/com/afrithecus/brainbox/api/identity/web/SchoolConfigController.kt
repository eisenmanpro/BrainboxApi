package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.identity.SchoolConfigService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.media.MediaObjectService
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
    private val mediaObjects: MediaObjectService,
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
        // Two-phase by design: the URL is only referenced once the admin saves the
        // config, so record the upload as unreferenced until then (D16/A23 follow-up).
        mediaObjects.register(stored.url, PURPOSE_BRANDING)
        return SchoolBrandingUploadPayload(kind = slot, url = stored.url, mediaType = stored.mediaType)
    }

    /**
     * Uploads one landing-gallery image for the school (multipart `file`, PNG/JPEG/GIF/WebP
     * up to 5 MB) and returns `{url, mediaType:"IMAGE"}`. Two-phase like the branding slots:
     * the admin adds the URL to `galleryUrls` and saves the config, so a cancelled edit never
     * changes the public landing page. School-scoped by the service's access check.
     */
    @PostMapping("/{schoolId}/config/gallery", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadGalleryImage(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @RequestPart("file") file: MultipartFile,
    ): com.afrithecus.brainbox.api.media.MediaUploadResponsePayload {
        service.requireBrandingUploadAccess(current, schoolId)
        val stored = mediaService.storeGalleryImage(file)
        mediaObjects.register(stored.url, PURPOSE_GALLERY)
        return stored
    }

    private companion object {
        /** The media_objects purpose for the two-phase branding upload lifecycle. */
        const val PURPOSE_BRANDING = "BRANDING"

        /** The media_objects purpose for the school's landing-gallery uploads. */
        const val PURPOSE_GALLERY = "GALLERY"
    }
}
