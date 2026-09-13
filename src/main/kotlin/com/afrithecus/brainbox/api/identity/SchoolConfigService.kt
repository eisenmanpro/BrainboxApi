package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.identity.entity.SchoolConfigEntity
import com.afrithecus.brainbox.api.identity.repository.SchoolConfigRepository
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.web.SchoolConfigPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * School branding configuration. ICT admins edit it; the report renderer reads
 * it so a server-produced PDF matches the on-device template
 * (docs/ongoing/api_reports_changes.md section 2).
 */
@Service
class SchoolConfigService(
    private val repository: SchoolConfigRepository,
    private val schoolRepository: SchoolRepository,
    private val codec: QuestionCodec,
    private val access: AdminSchoolAccess,
    private val auditLogService: AuditLogService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun get(admin: CurrentUser, schoolIdRaw: String): SchoolConfigPayload {
        val schoolId = requireSchool(schoolIdRaw)
        access.require(admin, schoolId)
        return payload(schoolId, repository.findById(schoolId).orElse(null))
    }

    @Transactional
    fun update(admin: CurrentUser, schoolIdRaw: String, request: SchoolConfigPayload): SchoolConfigPayload {
        val schoolId = requireSchool(schoolIdRaw)
        val actor = access.require(admin, schoolId)
        validate(request)
        val entity = repository.findById(schoolId).orElse(null)
            ?: SchoolConfigEntity().apply { this.schoolId = schoolId }
        entity.schoolName = request.schoolName.trim()
        entity.motto = request.motto?.trim()?.takeIf { it.isNotEmpty() }
        entity.logoUrl = request.logoUrl?.trim()?.takeIf { it.isNotEmpty() }
        entity.primaryColor = request.primaryColor?.trim()?.takeIf { it.isNotEmpty() }
        entity.address = request.address?.trim()?.takeIf { it.isNotEmpty() }
        entity.phone = request.phone?.trim()?.takeIf { it.isNotEmpty() }
        entity.email = request.email?.trim()?.takeIf { it.isNotEmpty() }
        entity.watermarkText = request.watermarkText?.trim()?.takeIf { it.isNotEmpty() }
        entity.academicCalendar = codec.toJson(request.academicCalendar.filter { it.isNotBlank() })
        entity.cbcStrands = codec.toJson(request.cbcStrands.filter { it.isNotBlank() })
        entity.updatedAt = clock.instant()
        repository.saveAndFlush(entity)
        auditLogService.record(schoolId, actor, "Updated school config")
        return payload(schoolId, entity)
    }

    /** Branding for report rendering, falling back to the directory name/logo. */
    @Transactional(readOnly = true)
    fun branding(schoolId: UUID?): SchoolConfigPayload {
        if (schoolId == null) {
            return SchoolConfigPayload(schoolId = "", schoolName = "")
        }
        val stored = repository.findById(schoolId).orElse(null)
        if (stored != null) return payload(schoolId, stored)
        val school = schoolRepository.findById(schoolId).orElse(null)
        return SchoolConfigPayload(
            schoolId = schoolId.toString(),
            schoolName = school?.name.orEmpty(),
            logoUrl = school?.logoUrl,
        )
    }

    // ------------------------------------------------------------ internals

    private fun requireSchool(raw: String): UUID {
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")
        schoolRepository.findById(id).orElse(null) ?: throw notFound("School not found")
        return id
    }

    private fun validate(request: SchoolConfigPayload) {
        val name = request.schoolName.trim()
        if (name.length < MIN_NAME) throw invalidArgument("schoolName must be at least " + MIN_NAME + " characters")
        if (name.length > MAX_NAME) throw invalidArgument("schoolName must be at most " + MAX_NAME + " characters")
        request.motto?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.length > MAX_MOTTO) throw invalidArgument("motto must be at most " + MAX_MOTTO + " characters")
        }
        request.primaryColor?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!HEX_COLOR.matches(it)) throw invalidArgument("primaryColor must be a 6-digit hex code (e.g. #4A5D23)")
        }
        request.email?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!EMAIL.matches(it)) throw invalidArgument("email is not a valid address")
        }
        request.watermarkText?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.length > MAX_WATERMARK) throw invalidArgument("watermarkText must be at most " + MAX_WATERMARK + " characters")
        }
        if (request.academicCalendar.size > MAX_CALENDAR_TERMS) {
            throw invalidArgument("academicCalendar must have at most " + MAX_CALENDAR_TERMS + " entries")
        }
    }

    private fun payload(schoolId: UUID, entity: SchoolConfigEntity?): SchoolConfigPayload {
        val fallbackName = schoolRepository.findById(schoolId).orElse(null)?.name.orEmpty()
        return SchoolConfigPayload(
            schoolId = schoolId.toString(),
            schoolName = entity?.schoolName?.takeIf { it.isNotBlank() } ?: fallbackName,
            motto = entity?.motto,
            logoUrl = entity?.logoUrl,
            primaryColor = entity?.primaryColor,
            address = entity?.address,
            phone = entity?.phone,
            email = entity?.email,
            watermarkText = entity?.watermarkText,
            academicCalendar = codec.parseList(entity?.academicCalendar).orEmpty(),
            cbcStrands = codec.parseList(entity?.cbcStrands).orEmpty(),
        )
    }

    private companion object {
        const val MIN_NAME = 3
        const val MAX_NAME = 120
        const val MAX_MOTTO = 160
        const val MAX_WATERMARK = 120
        const val MAX_CALENDAR_TERMS = 12
        val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
