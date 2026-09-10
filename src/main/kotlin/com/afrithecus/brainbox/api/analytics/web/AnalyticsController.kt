package com.afrithecus.brainbox.api.analytics.web

import com.afrithecus.brainbox.api.analytics.AnalyticsService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Server-computed exam analytics (doc 02 §8). */
@RestController
@RequestMapping("/analytics")
class AnalyticsController(private val service: AnalyticsService) {

    @GetMapping("/student/{studentId}")
    fun studentPerformance(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable studentId: String,
    ): StudentPerformancePayload = service.studentPerformance(current, studentId)

    @GetMapping("/student/{studentId}/subject/{subjectId}")
    fun subjectDetail(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable studentId: String,
        @PathVariable subjectId: String,
    ): SubjectDetailPayload = service.subjectDetail(current, studentId, subjectId)

    @GetMapping("/class/{classId}")
    fun classAnalytics(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable classId: String,
    ): ClassAnalyticsPayload = service.classAnalytics(current, classId)
}
