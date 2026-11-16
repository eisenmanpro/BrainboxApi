package com.afrithecus.brainbox.api.identity.web

import jakarta.validation.constraints.Size

/**
 * Platform-moderator editorial write for a school listing (FEAT-01/FEAT-06).
 *
 * A school's own ICT admin can never call this: featuring is editorial, not
 * self-promotion, so the route is ADMIN-only and audited. Every field is
 * additive; the list payload falls back to the legacy logo when [imageUrls] is
 * empty.
 */
data class SchoolEditorialPayload(
    val schoolId: String,
    val name: String,
    val imageUrls: List<String> = emptyList(),
    val isFeatured: Boolean = false,
    val featuredTagline: String? = null,
    val story: String? = null,
    val updatedAt: Long = 0L,
)

/** Body of PUT /admin/schools/{schoolId}/editorial. */
data class UpdateSchoolEditorialRequest(
    @field:Size(max = 4)
    val imageUrls: List<@Size(max = 512) String> = emptyList(),
    val isFeatured: Boolean = false,
    @field:Size(max = 255)
    val featuredTagline: String? = null,
    @field:Size(max = 5000)
    val story: String? = null,
)
