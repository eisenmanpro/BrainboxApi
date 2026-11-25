package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.identity.entity.SchoolConfigEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolDetailEntity
import com.afrithecus.brainbox.api.identity.repository.SchoolConfigRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolDetailRepository
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.web.SchoolClassPayload
import com.afrithecus.brainbox.api.identity.web.SchoolConfigPayload
import com.afrithecus.brainbox.api.identity.web.TermDatePayload
import com.afrithecus.brainbox.api.media.MediaObjectService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException
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
    private val detailRepository: SchoolDetailRepository,
    private val classRepository: TeacherClassRepository,
    private val codec: QuestionCodec,
    private val mapper: ObjectMapper,
    private val access: AdminSchoolAccess,
    private val auditLogService: AuditLogService,
    private val mediaObjects: MediaObjectService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun get(admin: CurrentUser, schoolIdRaw: String): SchoolConfigPayload {
        val schoolId = requireSchool(schoolIdRaw)
        access.require(admin, schoolId)
        return payload(schoolId, repository.findById(schoolId).orElse(null))
    }

    /**
     * Access check for the branding image upload (D16/A23); returns the resolved school
     * id so the controller can hand the stored URL back to the same admin.
     */
    @Transactional(readOnly = true)
    fun requireBrandingUploadAccess(admin: CurrentUser, schoolIdRaw: String): UUID {
        val schoolId = requireSchool(schoolIdRaw)
        access.require(admin, schoolId)
        return schoolId
    }

    @Transactional
    fun update(admin: CurrentUser, schoolIdRaw: String, request: SchoolConfigPayload): SchoolConfigPayload {
        val schoolId = requireSchool(schoolIdRaw)
        val actor = access.require(admin, schoolId)
        validate(request)
        val entity = repository.findById(schoolId).orElse(null)
            ?: SchoolConfigEntity().apply { this.schoolId = schoolId }
        // Branding objects are written before the config that references them; the
        // previous values are needed to remove the object this write replaces.
        val previousLogo = entity.logoUrl
        val previousStamp = entity.stampUrl
        val previousGallery = codec.parseList(entity.galleryUrls).orEmpty()
        entity.schoolName = request.schoolName.trim()
        entity.motto = request.motto?.trim()?.takeIf { it.isNotEmpty() }
        entity.logoUrl = request.logoUrl?.trim()?.takeIf { it.isNotEmpty() }
        entity.stampUrl = request.stampUrl?.trim()?.takeIf { it.isNotEmpty() }
        entity.primaryColor = request.primaryColor?.trim()?.takeIf { it.isNotEmpty() }
        entity.address = request.address?.trim()?.takeIf { it.isNotEmpty() }
        entity.phone = request.phone?.trim()?.takeIf { it.isNotEmpty() }
        entity.email = request.email?.trim()?.takeIf { it.isNotEmpty() }
        entity.watermarkText = request.watermarkText?.trim()?.takeIf { it.isNotEmpty() }
        entity.academicCalendar = codec.toJson(request.academicCalendar.filter { it.isNotBlank() })
        request.termDates?.let { entity.termDates = mapper.writeValueAsString(cleanTermDates(it)) }
        entity.cbcStrands = codec.toJson(request.cbcStrands.filter { it.isNotBlank() })
        entity.rooms = codec.toJson(cleanRooms(request.rooms))
        request.classes?.let { entity.classes = mapper.writeValueAsString(cleanClasses(it)) }
        request.galleryUrls?.let { entity.galleryUrls = codec.toJson(cleanGalleryUrls(it)) }
        // Findings round: the public school card/dialog shows the school's own profile data.
        // NULL means "leave unchanged" (older/Android clients omit these fields entirely), an
        // explicit blank string clears; otherwise a client that has no input for a field would
        // wipe the value another admin saved.
        request.website?.let { entity.website = it.trim().takeIf { v -> v.isNotEmpty() } }
        request.type?.let { entity.schoolType = it.trim().takeIf { v -> v.isNotEmpty() } }
        request.hours?.let { entity.schoolHours = it.trim().takeIf { v -> v.isNotEmpty() } }
        request.about?.let { entity.about = it.trim().takeIf { v -> v.isNotEmpty() } }
        request.facilities?.let { entity.facilities = codec.toJson(cleanProfileList(it, MAX_FACILITIES, MAX_FACILITY_NAME)) }
        request.highlights?.let { entity.highlights = codec.toJson(cleanProfileList(it, MAX_HIGHLIGHTS, MAX_HIGHLIGHT_NAME)) }
        entity.updatedAt = clock.instant()
        repository.saveAndFlush(entity)
        // Renaming the school must rename the canonical school row too: the public landing
        // list renders schools.name, so a config-only rename made the old row linger (the
        // "rename duplicates the school" finding).
        schoolRepository.findById(schoolId).ifPresent { school ->
            val renamed = request.schoolName.trim()
            if (renamed.isNotEmpty() && school.name != renamed) {
                school.name = renamed
                schoolRepository.save(school)
            }
        }
        syncPublicDetail(schoolId, entity)
        // Claim the uploads this config keeps, then delete the previous logo/stamp
        // objects it just replaced (D16/A23 follow-up: no orphaned branding bytes).
        mediaObjects.claim(entity.logoUrl)
        mediaObjects.claim(entity.stampUrl)
        mediaObjects.discardSuperseded(
            previous = listOf(previousLogo, previousStamp),
            keep = listOf(entity.logoUrl, entity.stampUrl),
        )
        // The school gallery is the same two-phase lifecycle: claim what the config keeps,
        // remove the uploaded object a replaced slot no longer references.
        val savedGallery = codec.parseList(entity.galleryUrls).orEmpty()
        savedGallery.forEach { mediaObjects.claim(it) }
        mediaObjects.discardSuperseded(previous = previousGallery, keep = savedGallery)
        auditLogService.record(schoolId, actor, "Updated school config")
        return payload(schoolId, entity)
    }

    /**
     * The school's class catalogue, without branding or an access check. Used by the public signup
     * directory so the form can offer the school's own grades and classes (gaps.md item 1C/E).
     */
    @Transactional(readOnly = true)
    fun configuredClasses(schoolId: UUID): List<SchoolClassPayload> =
        parseClasses(repository.findById(schoolId).orElse(null)?.classes)

    /**
     * Rewrites the signup class catalogue (`school_configs.classes`) from the school's live
     * `teacher_classes` rows.
     *
     * Data-model decision (findings round, admin School Config redesign): **teacher_classes is
     * authoritative for class instances** — it owns the teacher, the learners' memberships and
     * the exam class tags. The catalogue is only the signup/create picker's snapshot, so the
     * admin class CRUD calls this after every create/rename/delete to keep the two from drifting.
     * A config row is created when the school has none yet.
     */
    @Transactional
    fun syncClassCatalogue(schoolId: UUID, removedNames: Collection<String> = emptyList()) {
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val entity = repository.findById(schoolId).orElse(null) ?: SchoolConfigEntity().apply {
            this.schoolId = schoolId
            schoolName = schoolRepository.findById(schoolId).orElse(null)?.name.orEmpty()
            createdAt = clock.instant()
        }
        // Merge, never blind-replace: the catalogue may legitimately hold classes that have no
        // teacher_classes row yet (the school configured them before assigning a teacher), and a
        // class write must not silently delete those from the signup picker. Only the name this
        // operation removed is dropped.
        val removed = removedNames.map { it.trim().lowercase() }.toSet()
        val merged = LinkedHashMap<String, SchoolClassPayload>()
        parseClasses(entity.classes).forEach { row ->
            if (row.name.lowercase() !in removed) merged[row.name.lowercase()] = row
        }
        classes.forEach { row ->
            merged[row.name.lowercase()] = SchoolClassPayload(
                name = row.name,
                gradeLevel = row.gradeLevel,
                stream = row.stream,
            )
        }
        entity.classes = mapper.writeValueAsString(merged.values.toList())
        entity.updatedAt = clock.instant()
        repository.saveAndFlush(entity)
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
        // D16/A23: branding images are fetched server-side while rendering, so only a plain
        // http(s) URL (or an inline data:image URL used by tests) is accepted.
        validateImageUrl("logoUrl", request.logoUrl)
        validateImageUrl("stampUrl", request.stampUrl)
        if (request.academicCalendar.size > MAX_CALENDAR_TERMS) {
            throw invalidArgument("academicCalendar must have at most " + MAX_CALENDAR_TERMS + " entries")
        }
        request.termDates?.let { terms ->
            if (terms.size > MAX_CALENDAR_TERMS) {
                throw invalidArgument("termDates must have at most " + MAX_CALENDAR_TERMS + " entries")
            }
            val names = mutableSetOf<String>()
            terms.forEach { term ->
                val name = term.name.trim()
                if (name.isEmpty()) throw invalidArgument("Every term needs a name")
                if (name.length > MAX_TERM_NAME) throw invalidArgument("Term names must be at most " + MAX_TERM_NAME + " characters")
                if (!names.add(name.lowercase())) throw invalidArgument("Term names must be unique")
                val start = parseTermDate(term.start)
                val end = parseTermDate(term.end)
                if (start.isAfter(end)) throw invalidArgument("Term " + name + " starts after it ends")
            }
        }
        request.galleryUrls?.let { gallery ->
            if (gallery.size > MAX_GALLERY) {
                throw invalidArgument("galleryUrls must have at most " + MAX_GALLERY + " entries")
            }
            gallery.forEach { validateGalleryUrl(it) }
        }
        request.website?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.length > MAX_WEBSITE) throw invalidArgument("website must be at most " + MAX_WEBSITE + " characters")
            val parsed = runCatching { java.net.URI(it) }.getOrNull()
            if (parsed?.scheme?.lowercase() !in setOf("http", "https") || parsed?.host.isNullOrBlank()) {
                throw invalidArgument("website must be an http(s) URL")
            }
        }
        request.type?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.length > MAX_TYPE) throw invalidArgument("type must be at most " + MAX_TYPE + " characters")
        }
        request.hours?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.length > MAX_HOURS) throw invalidArgument("hours must be at most " + MAX_HOURS + " characters")
        }
        request.about?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.length > MAX_ABOUT) throw invalidArgument("about must be at most " + MAX_ABOUT + " characters")
        }
        request.facilities?.let { values ->
            if (values.size > MAX_FACILITIES) throw invalidArgument("facilities must have at most " + MAX_FACILITIES + " entries")
            values.filter { it.isNotBlank() }.forEach {
                if (it.trim().length > MAX_FACILITY_NAME) throw invalidArgument("facility names must be at most " + MAX_FACILITY_NAME + " characters")
            }
        }
        request.highlights?.let { values ->
            if (values.size > MAX_HIGHLIGHTS) throw invalidArgument("highlights must have at most " + MAX_HIGHLIGHTS + " entries")
            values.filter { it.isNotBlank() }.forEach {
                if (it.trim().length > MAX_HIGHLIGHT_NAME) throw invalidArgument("highlights must be at most " + MAX_HIGHLIGHT_NAME + " characters")
            }
        }
        if (request.rooms.size > MAX_ROOMS) throw invalidArgument("rooms must have at most " + MAX_ROOMS + " entries")
        request.rooms.filter { it.isNotBlank() }.forEach {
            if (it.trim().length > MAX_ROOM_NAME) throw invalidArgument("room names must be at most " + MAX_ROOM_NAME + " characters")
        }
        val classes = request.classes ?: return
        if (classes.size > MAX_CLASSES) {
            throw invalidArgument("classes must have at most " + MAX_CLASSES + " entries")
        }
        classes.forEach { row ->
            if (row.name.trim().isEmpty()) throw invalidArgument("every class needs a name")
            if (row.name.trim().length > MAX_CLASS_NAME) throw invalidArgument("class names must be at most " + MAX_CLASS_NAME + " characters")
            if (row.gradeLevel.trim().isEmpty()) throw invalidArgument("every class needs a grade level")
            if (row.gradeLevel.trim().length > MAX_GRADE_NAME) throw invalidArgument("grade levels must be at most " + MAX_GRADE_NAME + " characters")
            row.stream?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (it.length > MAX_STREAM_NAME) throw invalidArgument("streams must be at most " + MAX_STREAM_NAME + " characters")
            }
        }
    }

    private fun validateImageUrl(field: String, raw: String?) {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (value.length > MAX_IMAGE_URL) throw invalidArgument(field + " must be at most " + MAX_IMAGE_URL + " characters")
        if (value.startsWith("data:image/", ignoreCase = true)) return
        val parsed = runCatching { java.net.URI(value) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        if (parsed == null || (scheme != "http" && scheme != "https") || parsed.host.isNullOrBlank()) {
            throw invalidArgument(field + " must be an http(s) image URL")
        }
    }

    /** Trim, drop incomplete rows and de-duplicate by class name (case-insensitive). */
    private fun cleanClasses(rows: List<SchoolClassPayload>): List<SchoolClassPayload> {
        val seen = HashSet<String>()
        return rows.mapNotNull { row ->
            val name = row.name.trim()
            val grade = row.gradeLevel.trim()
            if (name.isEmpty() || grade.isEmpty() || !seen.add(name.lowercase())) return@mapNotNull null
            SchoolClassPayload(name = name, gradeLevel = grade, stream = row.stream?.trim()?.takeIf { it.isNotEmpty() })
        }.take(MAX_CLASSES)
    }

    /** Parse the stored class catalogue; a malformed/legacy value degrades to empty, never throws. */
    private fun parseClasses(raw: String?): List<SchoolClassPayload> {
        if (raw.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        val out = ArrayList<SchoolClassPayload>()
        for (index in 0 until node.size()) {
            val row = node.get(index)
            val name = row.get("name")?.asString()?.trim().orEmpty()
            val grade = row.get("gradeLevel")?.asString()?.trim().orEmpty()
            if (name.isEmpty() || grade.isEmpty()) continue
            out.add(
                SchoolClassPayload(
                    name = name,
                    gradeLevel = grade,
                    stream = row.get("stream")?.asString()?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }
        return out
    }

    /**
     * The landing gallery is rendered as <img src>, so an entry must be an absolute http(s)
     * URL or a path this server serves (/media/...). A data: URL is accepted only by the
     * branding slots, never here.
     */
    private fun validateGalleryUrl(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        if (value.length > MAX_IMAGE_URL) throw invalidArgument("galleryUrls entries must be at most " + MAX_IMAGE_URL + " characters")
        if (value.startsWith("/media/") && !value.contains("..")) return
        val parsed = runCatching { java.net.URI(value) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        if (parsed == null || (scheme != "http" && scheme != "https") || parsed.host.isNullOrBlank()) {
            throw invalidArgument("galleryUrls entries must be http(s) URLs or /media paths")
        }
    }

    /** Trim, drop blanks, de-duplicate and cap at the four images the landing renders. */
    private fun cleanGalleryUrls(urls: List<String>): List<String> =
        urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_GALLERY)

    private fun parseTermDate(raw: String): LocalDate =
        try {
            LocalDate.parse(raw.trim())
        } catch (failure: DateTimeParseException) {
            throw invalidArgument("Term dates must use yyyy-MM-dd")
        }

    private fun cleanTermDates(terms: List<TermDatePayload>): List<TermDatePayload> =
        terms.map { term ->
            TermDatePayload(
                name = term.name.trim(),
                start = parseTermDate(term.start).toString(),
                end = parseTermDate(term.end).toString(),
            )
        }

    private fun parseTermDates(raw: String?): List<TermDatePayload> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            mapper.readValue(raw, Array<TermDatePayload>::class.java).toList()
        }.getOrElse { emptyList() }
    }

    /** Trim, drop blanks and de-duplicate while preserving the caller's order. */
    private fun cleanRooms(rooms: List<String>): List<String> =
        rooms.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Trim, drop blanks, de-duplicate and cap a public-profile list (facilities/highlights). */
    private fun cleanProfileList(values: List<String>, max: Int, maxLength: Int): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(max).map { it.take(maxLength) }

    private fun payload(schoolId: UUID, entity: SchoolConfigEntity?): SchoolConfigPayload {
        val fallbackName = schoolRepository.findById(schoolId).orElse(null)?.name.orEmpty()
        return SchoolConfigPayload(
            schoolId = schoolId.toString(),
            schoolName = entity?.schoolName?.takeIf { it.isNotBlank() } ?: fallbackName,
            motto = entity?.motto,
            logoUrl = entity?.logoUrl,
            stampUrl = entity?.stampUrl,
            primaryColor = entity?.primaryColor,
            address = entity?.address,
            phone = entity?.phone,
            email = entity?.email,
            watermarkText = entity?.watermarkText,
            academicCalendar = codec.parseList(entity?.academicCalendar).orEmpty(),
            termDates = parseTermDates(entity?.termDates),
            cbcStrands = codec.parseList(entity?.cbcStrands).orEmpty(),
            rooms = codec.parseList(entity?.rooms).orEmpty(),
            classes = parseClasses(entity?.classes),
            galleryUrls = codec.parseList(entity?.galleryUrls).orEmpty(),
            website = entity?.website,
            type = entity?.schoolType,
            hours = entity?.schoolHours,
            about = entity?.about,
            facilities = codec.parseList(entity?.facilities).orEmpty(),
            highlights = codec.parseList(entity?.highlights).orEmpty(),
        )
    }

    /**
     * The public school directory reads school_details; without this sync an ICT admin's
     * contact/type/hours/facilities edits never reached the landing dialog ("information
     * unavailable though the admin configured it"). Fields the config leaves blank keep the
     * existing detail values, so this can never blank real public data.
     */
    private fun syncPublicDetail(schoolId: UUID, config: SchoolConfigEntity) {
        val existing = detailRepository.findById(schoolId).orElse(null)
        val detail = existing ?: SchoolDetailEntity().apply { this.schoolId = schoolId }
        config.phone?.trim()?.takeIf { it.isNotEmpty() }?.let { detail.phone = it }
        config.email?.trim()?.takeIf { it.isNotEmpty() }?.let { detail.email = it }
        config.website?.trim()?.takeIf { it.isNotEmpty() }?.let { detail.website = it }
        config.schoolType?.trim()?.takeIf { it.isNotEmpty() }?.let { detail.type = it }
        config.schoolHours?.trim()?.takeIf { it.isNotEmpty() }?.let { detail.hours = it }
        val facilities = codec.parseList(config.facilities).orEmpty()
        if (facilities.isNotEmpty()) detail.facilities = codec.toJson(facilities)
        if (existing == null && detail.gradeLevels.isBlank()) {
            detail.gradeLevels = parseClasses(config.classes).map { it.gradeLevel }.distinct().joinToString(", ")
        }
        if (existing == null && detail.phone.isBlank()) detail.phone = config.phone.orEmpty()
        if (existing == null && detail.email.isBlank()) detail.email = config.email.orEmpty()
        if (existing != null || detailHasContent(detail)) detailRepository.save(detail)
    }

    private fun detailHasContent(detail: SchoolDetailEntity): Boolean =
        detail.phone.isNotBlank() || detail.email.isNotBlank() || detail.website.isNotBlank() ||
            detail.type.isNotBlank() || detail.hours.isNotBlank() || !detail.facilities.isNullOrBlank()

    private companion object {
        const val MIN_NAME = 3
        const val MAX_NAME = 120
        const val MAX_MOTTO = 160
        const val MAX_WATERMARK = 120
        const val MAX_IMAGE_URL = 2048
        const val MAX_CALENDAR_TERMS = 12
        const val MAX_TERM_NAME = 80
        const val MAX_ROOMS = 100
        const val MAX_ROOM_NAME = 120
        const val MAX_CLASSES = 200
        const val MAX_GALLERY = 4
        const val MAX_WEBSITE = 255
        const val MAX_TYPE = 64
        const val MAX_HOURS = 120
        const val MAX_ABOUT = 2000
        const val MAX_FACILITIES = 20
        const val MAX_FACILITY_NAME = 120
        const val MAX_HIGHLIGHTS = 10
        const val MAX_HIGHLIGHT_NAME = 120
        const val MAX_CLASS_NAME = 120
        const val MAX_GRADE_NAME = 64
        const val MAX_STREAM_NAME = 64
        val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
