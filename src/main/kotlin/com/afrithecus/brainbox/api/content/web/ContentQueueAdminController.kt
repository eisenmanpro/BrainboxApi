package com.afrithecus.brainbox.api.content.web

import com.afrithecus.brainbox.api.content.ContentQueueAdminService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * H2 generation queue runtime surface. ADMIN only, matching the other admin
 * controllers. It exposes the live pause/source policy and queue depth, and lets
 * an operator pause or narrow autonomous generation without a redeploy:
 *
 *  - pause/resume stop the worker claiming entirely; user-submitted jobs then
 *    wait in the queue rather than being lost.
 *  - sources narrow claiming to a subset (for example only USER), so a launch
 *    can keep serving interactive requests while batch/proactive generation is
 *    switched off.
 */
@RestController
@RequestMapping("/admin/content/queue")
@PreAuthorize("hasRole('ADMIN')")
class ContentQueueAdminController(private val service: ContentQueueAdminService) {

    /** Live policy plus queue depth, overall and per source. */
    @GetMapping
    fun summary(): ContentQueueSummary = service.summary()

    /** Pauses the worker (no claims until resume) and returns the new summary. */
    @PostMapping("/pause")
    fun pause(): ContentQueueSummary = service.pause()

    /** Resumes the worker and returns the new summary. */
    @PostMapping("/resume")
    fun resume(): ContentQueueSummary = service.resume()

    /** Sets the enabled sources and returns the new summary. */
    @PutMapping("/sources")
    fun sources(@RequestBody request: ContentQueueSourcesRequest): ContentQueueSummary =
        service.setSources(request.sources)
}
