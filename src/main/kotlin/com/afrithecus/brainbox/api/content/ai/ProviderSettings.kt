package com.afrithecus.brainbox.api.content.ai

/**
 * The resolved settings one OpenAI-compatible provider needs. Built from
 * [AppAiProperties.Provider] so the provider itself never reads configuration
 * keys and a provider can be pointed at any OpenAI-compatible endpoint.
 */
data class ProviderSettings(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** Empty means "use [model]" for the independent answer-key solve. */
    val verifyModel: String,
    val costProfile: ProviderCostProfile,
) {

    /** The model the verification call uses, falling back to the generation model. */
    fun verificationModel(): String = verifyModel.ifEmpty { model }

    companion object {

        /** Resolves one configured provider to its settings; a blank model picks [defaultModel]. */
        fun of(name: String, configured: AppAiProperties.Provider, defaultModel: String): ProviderSettings =
            ProviderSettings(
                name = name,
                baseUrl = configured.baseUrl.trim().trimEnd('/'),
                apiKey = configured.apiKey.trim(),
                model = configured.model.trim().ifEmpty { defaultModel },
                verifyModel = configured.verifyModel.trim(),
                costProfile = if (configured.promptMicrosPerMillion > 0L || configured.completionMicrosPerMillion > 0L) {
                    ProviderCostProfile(configured.promptMicrosPerMillion, configured.completionMicrosPerMillion)
                } else {
                    ProviderCostProfile.UNKNOWN
                },
            )
    }
}
