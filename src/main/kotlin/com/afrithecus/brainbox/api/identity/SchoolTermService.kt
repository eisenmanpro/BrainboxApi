package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.identity.repository.SchoolConfigRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.web.TermCalendarPayload
import com.afrithecus.brainbox.api.identity.web.TermDatePayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The school's academic calendar: the human labels plus their exact windows.
 *
 * Every "current term" answer goes through here so the web, exams, reports and
 * attendance SMS cannot disagree. When a school has not configured dates the
 * caller keeps its month-based fallback; this service never invents a window.
 */
@Service
class SchoolTermService(
    private val configRepository: SchoolConfigRepository,
    private val schoolRepository: SchoolRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun termDates(schoolId: UUID): List<TermDatePayload> =
        parse(configRepository.findById(schoolId).orElse(null)?.termDates)

    @Transactional(readOnly = true)
    fun academicCalendar(schoolId: UUID): List<String> {
        val raw = configRepository.findById(schoolId).orElse(null)?.academicCalendar ?: return emptyList()
        return runCatching { mapper.readValue(raw, Array<String>::class.java).toList() }.getOrElse { emptyList() }
    }

    @Transactional(readOnly = true)
    fun calendar(schoolIdRaw: String): TermCalendarPayload {
        val schoolId = runCatching { UUID.fromString(schoolIdRaw.trim()) }.getOrNull()
            ?: throw com.afrithecus.brainbox.api.common.error.notFound("School not found")
        val school = schoolRepository.findById(schoolId).orElse(null)
            ?: throw com.afrithecus.brainbox.api.common.error.notFound("School not found")
        val terms = termDates(schoolId)
        val today = LocalDate.now(clock)
        val current = terms.firstOrNull { term ->
            val start = runCatching { LocalDate.parse(term.start) }.getOrNull() ?: return@firstOrNull false
            val end = runCatching { LocalDate.parse(term.end) }.getOrNull() ?: return@firstOrNull false
            !today.isBefore(start) && !today.isAfter(end)
        }
        return TermCalendarPayload(
            schoolId = school.id.toString(),
            academicCalendar = academicCalendar(schoolId),
            termDates = terms,
            currentTerm = current?.name,
            currentTermStart = current?.start,
            currentTermEnd = current?.end,
        )
    }

    /** TERM_1/TERM_2/TERM_3 for the active configured term, or null between terms. */
    @Transactional(readOnly = true)
    fun currentTermKey(schoolId: UUID?): String? {
        if (schoolId == null) return null
        val name = calendar(schoolId.toString()).currentTerm ?: return null
        val match = Regex("(?i)term\\s*([123])").find(name) ?: return null
        return "TERM_" + match.groupValues[1]
    }

    private fun parse(raw: String?): List<TermDatePayload> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { mapper.readValue(raw, Array<TermDatePayload>::class.java).toList() }
            .getOrElse { emptyList() }
            .filter { it.name.isNotBlank() && it.start.isNotBlank() && it.end.isNotBlank() }
    }
}
