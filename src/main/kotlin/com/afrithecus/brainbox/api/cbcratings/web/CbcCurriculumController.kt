package com.afrithecus.brainbox.api.cbcratings.web

import com.afrithecus.brainbox.api.cbcratings.CbcAnalyticsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The CBC taxonomy as data, for any signed-in user.
 *
 * The teacher analytics surface exposes the same map under `/teacher/cbc/curriculum-map`,
 * but the strand and sub-strand pickers are not teacher-only: a learner submitting a CBC
 * project picks a strand too, and the doc requires those pickers to be data-driven rather
 * than hardcoded or free text (`api_phase7_changes.md` section I).
 */
@RestController
@RequestMapping("/cbc")
class CbcCurriculumController(private val service: CbcAnalyticsService) {

    /** Strands and sub-strands (sub-strands carry their parent's code). */
    @GetMapping("/curriculum-map")
    fun curriculumMap(@AuthenticationPrincipal current: CurrentUser): CbcCurriculumMapPayload =
        service.curriculumMap()
}
