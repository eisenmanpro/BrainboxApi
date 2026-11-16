package com.afrithecus.brainbox.api.identity.web

/**
 * A branding image stored by the media service (D16/A23). The admin app then saves
 * [url] into [SchoolConfigPayload.logoUrl] or [SchoolConfigPayload.stampUrl], so the
 * upload and the config update stay separate steps and a cancelled form leaves the
 * saved branding untouched.
 */
data class SchoolBrandingUploadPayload(
    val kind: String,
    val url: String,
    val mediaType: String = "IMAGE",
)

/**
 * Branding/academic configuration for a school (client models.analytics
 * SchoolConfig in repository/admin/SchoolAnalyticsRepository.kt).
 */
data class SchoolConfigPayload(
    val schoolId: String,
    val schoolName: String,
    val motto: String? = null,
    val logoUrl: String? = null,
    /** School stamp image for reports; null = the standard Exam Office stamp (D16/A23). */
    val stampUrl: String? = null,
    val primaryColor: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val watermarkText: String? = null,
    val academicCalendar: List<String> = emptyList(),
    val cbcStrands: List<String> = emptyList(),
    /** Server-owned room list for the timetable/room pickers (TT-3). */
    val rooms: List<String> = emptyList(),
    /**
     * The school's class catalogue; teacher settings pick classes/grades from it (N3).
     * NULL means "leave the stored catalogue untouched": PUT is a full replace and older
     * clients (and the branding-only editor) omit the field, so binding a default empty
     * list here would wipe every class a teacher can pick. A non-null list replaces it.
     */
    val classes: List<SchoolClassPayload>? = null,
    /**
     * The school's own landing-gallery images (findings round: admin gallery upload).
     * Up to 4 absolute http(s) URLs or /media paths. NULL means "leave untouched" (older
     * clients); an empty list clears the gallery so the platform editorial images show again.
     */
    val galleryUrls: List<String>? = null,
)
