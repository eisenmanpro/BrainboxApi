package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.SchoolEditorialPayload
import com.afrithecus.brainbox.api.identity.web.UpdateSchoolEditorialRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The platform-moderator editorial surface for the public school directory
 * (FEAT-01 server half, FEAT-06 rule).
 *
 * Only a platform ADMIN can read or write these fields. A school's ICT admin has
 * no route here: [AdminSchoolController] is school-scoped and deliberately does
 * not expose featuring, so a school can never promote its own listing. Every
 * write is audited with the actor and the resulting state.
 */
@Service
class SchoolEditorialService(
    private val schoolRepository: SchoolRepository,
    private val userRepository: UserRepository,
    private val auditLogService: AuditLogService,
    private val mapper: ObjectMapper,
) {

    @Transactional(readOnly = true)
    fun read(actorId: UUID, schoolIdRaw: String): SchoolEditorialPayload {
        val (actor, school) = requireAdminAndSchool(actorId, schoolIdRaw)
        return payload(school)
    }

    @Transactional
    fun update(actorId: UUID, schoolIdRaw: String, request: UpdateSchoolEditorialRequest): SchoolEditorialPayload {
        val (actor, school) = requireAdminAndSchool(actorId, schoolIdRaw)
        val images = request.imageUrls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (images.size > MAX_IMAGES) throw invalidArgument("A school gallery may have at most " + MAX_IMAGES + " images")
        images.forEach { validateImageUrl(it) }
        school.imageUrls = if (images.isEmpty()) null else mapper.writeValueAsString(images)
        school.isFeatured = request.isFeatured
        school.featuredTagline = request.featuredTagline?.trim()?.takeIf { it.isNotEmpty() }
        school.story = request.story?.trim()?.takeIf { it.isNotEmpty() }
        schoolRepository.save(school)
        auditLogService.recordPlatform(
            actor = actor,
            action = if (school.isFeatured) "Featured school listing" else "Updated school editorial",
            target = school.id.toString(),
            detail = school.name + (if (school.isFeatured) " (featured)" else ""),
        )
        return payload(school)
    }

    private fun requireAdminAndSchool(actorId: UUID, schoolIdRaw: String): Pair<UserEntity, SchoolEntity> {
        val actor = userRepository.findById(actorId).orElse(null) ?: throw notFound("User not found")
        // Defense in depth: the route is ADMIN-only, and the service refuses a non-admin
        // caller too so a future internal call cannot bypass the editorial rule.
        if (actor.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Platform moderator access required")
        }
        val id = runCatching { UUID.fromString(schoolIdRaw) }.getOrNull()
            ?: throw invalidArgument("school id is not a valid identifier")
        val school = schoolRepository.findById(id).orElse(null) ?: throw notFound("School not found")
        return actor to school
    }

    private fun validateImageUrl(raw: String) {
        if (raw.length > 512) throw invalidArgument("An image URL must be at most 512 characters")
        val pathOnly = raw.startsWith("/")
        val absolute = raw.startsWith("https://") || raw.startsWith("http://")
        if (!pathOnly && !absolute) {
            throw invalidArgument("An image URL must be absolute http(s) or a server-relative /media path")
        }
        // A server-relative path must stay inside the media surface this API serves;
        // otherwise the report/list renderer would be pointed at an internal route.
        if (pathOnly && !raw.startsWith("/media/")) {
            throw invalidArgument("A server-relative image URL must start with /media/")
        }
    }

    private fun payload(school: SchoolEntity) = SchoolEditorialPayload(
        schoolId = school.id.toString(),
        name = school.name,
        imageUrls = parseList(school.imageUrls),
        isFeatured = school.isFeatured,
        featuredTagline = school.featuredTagline,
        story = school.story,
        updatedAt = school.updatedAt.toEpochMilli(),
    )

    private fun parseList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).mapNotNull { node.get(it).asString() }
    }

    private companion object {
        const val MAX_IMAGES = 4
    }
}
