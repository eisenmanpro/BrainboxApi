package com.afrithecus.brainbox.api.studentanalytics.web

import com.afrithecus.brainbox.api.achievements.web.UserAchievementsPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandRatingPayload
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.contract.web.LearningContractPayload
import com.afrithecus.brainbox.api.feedback.web.TeacherFeedbackPayload
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.studentanalytics.StudentAnalyticsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Teacher student analytics (doc 04 section 14). */
@RestController
@RequestMapping("/teacher/analytics")
class StudentAnalyticsController(
    private val service: StudentAnalyticsService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/student/{studentId}")
    fun studentAnalytics(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
        @PathVariable studentId: String,
    ): StudentAnalyticsPayload = service.studentAnalytics(teacher(currentUser), studentId)

    @GetMapping("/student/{studentId}/subject-performance")
    fun subjectPerformance(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable studentId: String,
    ): List<SubjectPerformancePayload> = service.subjectPerformance(teacher(currentUser), studentId)

    @GetMapping("/student/{studentId}/cbc-competencies")
    fun cbcCompetencies(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable studentId: String,
    ): List<CbcStrandRatingPayload> = service.cbcCompetencies(teacher(currentUser), studentId)

    @GetMapping("/student/{studentId}/attendance")
    fun attendance(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable studentId: String,
        @RequestParam(required = false) startDate: Long?,
        @RequestParam(required = false) endDate: Long?,
    ): List<StudentAttendancePayload> =
        service.attendance(teacher(currentUser), studentId, startDate ?: 0, endDate ?: 0)

    @GetMapping("/student/{studentId}/engagement")
    fun engagement(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable studentId: String,
    ): EngagementScorePayload = service.engagement(teacher(currentUser), studentId)

    @GetMapping("/student/{studentId}/homework")
    fun homework(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable studentId: String,
    ): List<StudentHomeworkPayload> = service.homeworkHistory(teacher(currentUser), studentId)

    @GetMapping("/student/{studentId}/feedback")
    fun feedback(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable studentId: String,
    ): List<TeacherFeedbackPayload> = service.feedbackHistory(teacher(currentUser), studentId)

    @GetMapping("/student/{studentId}/learning-contract")
    fun learningContract(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable studentId: String,
    ): LearningContractPayload? = service.learningContract(teacher(currentUser), studentId)

    @GetMapping("/student/{studentId}/conferences")
    fun conferences(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable studentId: String,
    ): List<Any?> {
        service.studentAnalytics(teacher(currentUser), studentId)
        return emptyList()
    }

    @GetMapping("/class/{classId}/comparisons")
    fun comparisons(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
    ): ClassComparisonsPayload = service.classComparisons(teacher(currentUser), classId)

    @GetMapping("/student/{studentId}/achievements")
    fun achievements(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable studentId: String,
    ): UserAchievementsPayload = service.achievements(teacher(currentUser), studentId)
}
