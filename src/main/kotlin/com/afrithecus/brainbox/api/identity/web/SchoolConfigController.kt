package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.identity.SchoolConfigService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** School branding configuration edited by ICT admins. */
@RestController
@RequestMapping("/admin/schools")
@PreAuthorize("hasRole('ADMIN')")
class SchoolConfigController(private val service: SchoolConfigService) {

    @GetMapping("/{schoolId}/config")
    fun get(@PathVariable schoolId: String): SchoolConfigPayload = service.get(schoolId)

    @PutMapping("/{schoolId}/config")
    fun update(
        @PathVariable schoolId: String,
        @RequestBody request: SchoolConfigPayload,
    ): SchoolConfigPayload = service.update(schoolId, request)
}
