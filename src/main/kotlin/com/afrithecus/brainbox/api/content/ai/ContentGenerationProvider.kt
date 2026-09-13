package com.afrithecus.brainbox.api.content.ai

/** The generation provider seam (Phase 7.2); [ContentRouter] is its only caller. */
interface ContentGenerationProvider {

    val name: String

    fun generate(request: GenerationRequest): GenerationResult
}
