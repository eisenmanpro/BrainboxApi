package com.afrithecus.brainbox.api.about.web

import com.afrithecus.brainbox.api.about.AboutService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public About + FAQ reads (docs/ongoing/product_ops_roadmap.md item 7). Both clients
 * fetch these before a session exists, so they are permitted anonymously in SecurityConfig.
 */
@RestController
@RequestMapping("/about")
class AboutController(private val service: AboutService) {

    @GetMapping("/narratives")
    fun narratives(): NarrativePayload = service.narratives()

    @GetMapping("/human-elements")
    fun humanElements(): List<HumanElementPayload> = service.humanElements()

    @GetMapping("/social-proof")
    fun socialProof(): SocialProofPayload = service.socialProof()

    @GetMapping("/practical-details")
    fun practicalDetails(): PracticalDetailPayload = service.practicalDetails()

    @GetMapping("/faqs")
    fun faqs(): FaqPayload = service.faqs()
}
