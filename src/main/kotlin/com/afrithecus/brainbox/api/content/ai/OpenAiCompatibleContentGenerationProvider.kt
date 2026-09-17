package com.afrithecus.brainbox.api.content.ai

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * One OpenAI-compatible chat-completions provider (Phase 7.2, generalised in this
 * pass). DeepSeek and OpenAI (and any gateway that speaks the same wire format)
 * differ only in base URL, key, model and price, so there is one implementation
 * configured per endpoint rather than one class per vendor.
 *
 * It asks for strict JSON, extracts the assistant content and usage counters, and
 * maps the JSON into a [GenerationResult] or an [AnswerVerificationResult]. It is
 * dependency-free (JDK [HttpClient]) so the runtime classpath stays Spring-Boot
 * starters only. The result records which provider served it and its priced cost,
 * which the router and the `model_calls` capture rely on.
 *
 * [verifyAnswerKeys] is a separate model interaction with its own system prompt: it
 * never receives the stored answer keys, so a confident-but-wrong key cannot make
 * the independent solve agree with it by construction.
 */
class OpenAiCompatibleContentGenerationProvider(
    private val settings: ProviderSettings,
    private val mapper: ObjectMapper,
) : RoutedGenerationProvider {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    override val name: String = settings.name

    /** The price the router ranks this provider by and the capture prices calls with. */
    override val costProfile: ProviderCostProfile = settings.costProfile

    override fun generate(request: GenerationRequest): GenerationResult {
        val model = settings.model.ifEmpty { DEFAULT_MODEL }
        val parsed = parse(send(mapper.writeValueAsString(chatRequest(model, request))), model)
        return parsed.copy(
            provider = name,
            costMicros = costProfile.costMicros(parsed.promptTokens, parsed.completionTokens),
        )
    }

    override fun verifyAnswerKeys(request: AnswerVerificationRequest): AnswerVerificationResult {
        val model = settings.verificationModel().ifEmpty { DEFAULT_MODEL }
        val parsed = parseVerification(
            send(mapper.writeValueAsString(verificationChatRequest(model, request))),
            model,
        )
        return parsed.copy(
            provider = name,
            costMicros = costProfile.costMicros(parsed.promptTokens, parsed.completionTokens),
        )
    }

    /** One authenticated chat-completions POST; failures map to SERVICE_UNAVAILABLE. */
    private fun send(body: String): String {
        val apiKey = settings.apiKey
        if (apiKey.isEmpty()) {
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "provider '" + name + "' has no api key configured")
        }
        if (settings.baseUrl.isEmpty()) {
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "provider '" + name + "' has no base url configured")
        }
        val url = settings.baseUrl + "/chat/completions"
        val httpRequest = HttpRequest.newBuilder(URI(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = try {
            client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (ex: Exception) {
            log.warn("Provider {} request failed: {}", name, ex.message)
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "provider " + name + " request failed: " + ex.message)
        }
        if (response.statusCode() !in 200..299) {
            log.warn("Provider {} returned {}: {}", name, response.statusCode(), response.body())
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "provider " + name + " returned " + response.statusCode())
        }
        return response.body()
    }

    // --------------------------------------------------------------- wire format

    private fun chatRequest(model: String, request: GenerationRequest): Map<String, Any?> = linkedMapOf(
        "model" to model,
        "messages" to listOf(
            mapOf("role" to "system", "content" to GenerationPrompts.systemPrompt(request.persona)),
            mapOf("role" to "user", "content" to GenerationPrompts.generationUserPrompt(request)),
        ),
        "temperature" to 0.2,
        "response_format" to mapOf("type" to "json_object"),
        "stream" to false,
    )

    private fun verificationChatRequest(model: String, request: AnswerVerificationRequest): Map<String, Any?> = linkedMapOf(
        "model" to model,
        "messages" to listOf(
            mapOf("role" to "system", "content" to GenerationPrompts.VERIFICATION_SYSTEM),
            mapOf("role" to "user", "content" to GenerationPrompts.verificationUserPrompt(request)),
        ),
        // Zero temperature: an independent solve should be deterministic, not creative.
        "temperature" to 0.0,
        "response_format" to mapOf("type" to "json_object"),
        "stream" to false,
    )

    private fun parse(body: String, fallbackModel: String): GenerationResult {
        val root = mapper.readTree(body)
        val choices = root.get("choices")?.takeIf { it.isArray && it.size() > 0 }
            ?: throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "provider " + name + " response has no choices")
        val content = choices.get(0).get("message")?.get("content")?.asString()
            ?: throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "provider " + name + " response has no message content")
        val usage = root.get("usage")
        val promptTokens = usage?.get("prompt_tokens")?.intValue() ?: 0
        val completionTokens = usage?.get("completion_tokens")?.intValue() ?: 0
        val model = root.get("model")?.asString()?.takeIf { it.isNotBlank() } ?: fallbackModel
        val parsed = try {
            mapper.readValue(content, GenerationResult::class.java)
        } catch (ex: Exception) {
            log.warn("Provider {} returned malformed content JSON: {}", name, ex.message)
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "provider " + name + " returned malformed JSON: " + ex.message)
        }
        return parsed.copy(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            model = parsed.model ?: model,
        )
    }

    private fun parseVerification(body: String, fallbackModel: String): AnswerVerificationResult {
        val root = mapper.readTree(body)
        val choices = root.get("choices")?.takeIf { it.isArray && it.size() > 0 }
            ?: throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "provider " + name + " response has no choices")
        val content = choices.get(0).get("message")?.get("content")?.asString()
            ?: throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "provider " + name + " response has no message content")
        val usage = root.get("usage")
        val promptTokens = usage?.get("prompt_tokens")?.intValue() ?: 0
        val completionTokens = usage?.get("completion_tokens")?.intValue() ?: 0
        val model = root.get("model")?.asString()?.takeIf { it.isNotBlank() } ?: fallbackModel
        val parsed = try {
            mapper.readValue(content, AnswerVerificationResult::class.java)
        } catch (ex: Exception) {
            log.warn("Provider {} returned malformed verification JSON: {}", name, ex.message)
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "provider " + name + " returned malformed JSON: " + ex.message)
        }
        return parsed.copy(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            model = parsed.model ?: model,
        )
    }

    private companion object {
        const val DEFAULT_MODEL = "deepseek-chat"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
