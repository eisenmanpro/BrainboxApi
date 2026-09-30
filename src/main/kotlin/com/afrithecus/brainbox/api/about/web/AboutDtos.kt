package com.afrithecus.brainbox.api.about.web

/**
 * About + FAQ contract. Field names match the Android AboutModels.kt and the web
 * AboutRepository, so one server payload serves both clients
 * (docs/ongoing/product_ops_roadmap.md item 7).
 */
data class NarrativePayload(
    val missionStatement: String,
    val story: String,
    val vision: String,
)

data class HumanElementPayload(
    val founderName: String,
    val profession: String,
    val photo: String? = null,
    val bio: String,
    val bioPersona: String,
)

data class PartnerPayload(
    val partnerName: String,
    val partnerPhoto: String? = null,
    val testimony: String,
)

data class SocialProofPayload(
    val userBase: Int,
    val clients: List<PartnerPayload> = emptyList(),
    val yearsOfService: Int,
)

data class PracticalDetailPayload(
    val location: String,
    val careerLink: String,
    val contactInfo: String,
)

data class FaqItemPayload(
    val id: String,
    val question: String,
    val answer: String,
)

data class FaqCategoryPayload(
    val id: String,
    val title: String,
    val items: List<FaqItemPayload>,
)

data class FaqPayload(
    val categories: List<FaqCategoryPayload>,
    /** Lets the clients cache and revalidate without refetching the whole body. */
    val version: String,
)
