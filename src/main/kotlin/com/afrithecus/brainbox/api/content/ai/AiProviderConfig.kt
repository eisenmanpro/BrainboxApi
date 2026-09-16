package com.afrithecus.brainbox.api.content.ai

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper

/**
 * Wires the OpenAI-compatible provider pool. DeepSeek is always in the pool once
 * `app.ai.enabled=true`; `app.ai.openai.enabled=true` adds a second, generic
 * endpoint. The routing provider is the single `@Primary`
 * {@link ContentGenerationProvider} the content router consumes; with AI disabled
 * the fail-loud disabled provider is the only bean, so nothing silently
 * fabricates content.
 */
@Configuration
class AiProviderConfig {

    @Bean
    @ConditionalOnProperty(name = ["app.ai.enabled"], havingValue = "true")
    fun deepSeekContentGenerationProvider(
        properties: AppAiProperties,
        mapper: ObjectMapper,
    ): OpenAiCompatibleContentGenerationProvider =
        OpenAiCompatibleContentGenerationProvider(
            ProviderSettings.of(DEEPSEEK_NAME, properties.deepseek, DEFAULT_DEEPSEEK_MODEL),
            mapper,
        )

    @Bean
    @ConditionalOnProperty(name = ["app.ai.enabled", "app.ai.openai.enabled"], havingValue = "true")
    fun openAiCompatibleContentGenerationProvider(
        properties: AppAiProperties,
        mapper: ObjectMapper,
    ): OpenAiCompatibleContentGenerationProvider =
        OpenAiCompatibleContentGenerationProvider(
            ProviderSettings.of(OPENAI_NAME, properties.openai, DEFAULT_OPENAI_MODEL),
            mapper,
        )

    @Bean
    @Primary
    @ConditionalOnProperty(name = ["app.ai.enabled"], havingValue = "true")
    fun routingContentGenerationProvider(
        candidates: List<RoutedGenerationProvider>,
        health: ProviderHealth,
        properties: AppAiProperties,
    ): ContentGenerationProvider =
        RoutingContentGenerationProvider(candidates, health, properties.routing.policy)

    @Bean
    @ConditionalOnProperty(name = ["app.ai.enabled"], havingValue = "false", matchIfMissing = true)
    fun disabledContentGenerationProvider(): ContentGenerationProvider = DisabledContentGenerationProvider()

    private companion object {
        const val DEEPSEEK_NAME = "deepseek"
        const val OPENAI_NAME = "openai"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
    }
}
