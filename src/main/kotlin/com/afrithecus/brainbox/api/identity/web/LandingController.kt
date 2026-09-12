package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.identity.SchoolDirectoryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Landing page content (unauthenticated). News now lives on /news. */
@RestController
@RequestMapping("/landing")
class LandingController(private val service: SchoolDirectoryService) {

    @GetMapping("/trending-schools")
    fun trendingSchools(@RequestParam(defaultValue = "10") limit: Int): List<SchoolListPayload> =
        service.trending(limit)
}
