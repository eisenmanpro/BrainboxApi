package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolSystemSettingsRepository
import com.afrithecus.brainbox.api.identity.web.SignupClassPayload
import com.afrithecus.brainbox.api.identity.web.SignupSchoolDetailPayload
import com.afrithecus.brainbox.api.identity.web.SignupSchoolPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Read model for the public signup form (gaps.md item 1C/E). The grade and class lists are the
 * school's own catalogue, configured by its ICT admin; signup refuses anything that is not in it.
 */
@Service
class SignupDirectoryService(
    private val schoolRepository: SchoolRepository,
    private val settingsRepository: SchoolSystemSettingsRepository,
    private val schoolConfigService: SchoolConfigService,
) {

    @Transactional(readOnly = true)
    fun schools(): List<SignupSchoolPayload> =
        schoolRepository.findAllByIsActiveTrueOrderByNameAsc().map { school ->
            SignupSchoolPayload(
                id = school.id.toString(),
                name = school.name,
                registrationOpen = registrationOpen(school.id),
            )
        }

    @Transactional(readOnly = true)
    fun school(rawId: String): SignupSchoolDetailPayload {
        val id = runCatching { UUID.fromString(rawId.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")
        val school = schoolRepository.findById(id).orElse(null) ?: throw notFound("School not found")
        val classes = schoolConfigService.configuredClasses(id)
            .map { SignupClassPayload(name = it.name.trim(), gradeLevel = it.gradeLevel.trim()) }
        return SignupSchoolDetailPayload(
            id = school.id.toString(),
            name = school.name,
            registrationOpen = registrationOpen(id),
            grades = classes.map { it.gradeLevel }.distinct(),
            classes = classes,
        )
    }

    private fun registrationOpen(schoolId: UUID): Boolean =
        settingsRepository.findById(schoolId).orElse(null)?.registrationOpen ?: true
}
