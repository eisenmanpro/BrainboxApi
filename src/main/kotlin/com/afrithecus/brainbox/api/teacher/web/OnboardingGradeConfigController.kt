package com.afrithecus.brainbox.api.teacher.web

import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.OnboardingGradeConfigPayload
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * N3-03: the onboarding grade picker called the route school/grade-configs/onboarding,
 * which the server never mapped (D7), so a fresh install had only an empty device
 * cache and a teacher could not pick a grade. The grades are derived from the
 * school's real class catalogue instead of a new assignment model.
 */
@RestController
@RequestMapping("/school")
@PreAuthorize("hasRole('TEACHER')")
class OnboardingGradeConfigController(
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val classRepository: TeacherClassRepository,
) {

    @GetMapping("/grade-configs/onboarding")
    fun gradeConfigsForOnboarding(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) schoolId: String?,
        @RequestParam(required = false) subRole: String?,
    ): List<OnboardingGradeConfigPayload> {
        val teacher = userRepository.findById(current.userId).orElseThrow { notFound("User not found") }
        val requested = parseSchool(schoolId) ?: teacher.schoolId
            ?: throw ApiException(ApiErrorCode.FORBIDDEN, "A teacher must belong to a school to onboard")
        // A teacher may only ask about their own school; the token decides.
        if (teacher.schoolId != null && requested != teacher.schoolId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "You can only choose grades in your own school")
        }
        val school = schoolRepository.findById(requested).orElse(null) ?: throw notFound("School not found")
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(requested)
        return classes.groupBy { it.gradeLevel }
            .toSortedMap()
            .map { (gradeLevel, rows) ->
                OnboardingGradeConfigPayload(
                    configId = "grade-" + slug(gradeLevel) + "-" + requested,
                    schoolId = requested.toString(),
                    gradeLevel = gradeLevel,
                    displayName = gradeLevel,
                    active = true,
                    createdAt = school.createdAt.toEpochMilli(),
                    subjects = rows.map { it.subject }.distinct().sorted(),
                )
            }
    }

    private fun parseSchool(raw: String?): UUID? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { UUID.fromString(it) }.getOrNull() ?: throw invalidArgument("schoolId is not a valid identifier")
        }

    private fun slug(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
