package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.identity.admin.IdentityAdminService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Public school discovery (doc 01 §9.2): search/all are reachable pre-auth for
 * onboarding; detail requires an authenticated user.
 */
@RestController
@RequestMapping("/schools")
class SchoolsController(private val service: IdentityAdminService) {

    @GetMapping("/search")
    fun search(@RequestParam query: String): List<SchoolPayload> = service.searchSchools(query)

    @GetMapping("/all")
    fun all(): List<SchoolPayload> = service.allSchools()

    @GetMapping("/{id}")
    fun detail(@PathVariable id: String): SchoolPayload = service.getSchool(id)
}
