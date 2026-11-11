package com.afrithecus.brainbox.api.curriculum.web

import com.afrithecus.brainbox.api.curriculum.SubjectCatalogService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The BrainBox subject catalogue (N3). Read-only to clients: subjects are
 * managed server-side so a teacher settings drop-down can gain a subject
 * without an app release.
 */
@RestController
@RequestMapping("/curriculum")
class SubjectController(private val service: SubjectCatalogService) {

    @GetMapping("/subjects")
    fun subjects(): List<SubjectPayload> = service.all()
}
