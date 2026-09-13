package com.afrithecus.brainbox.api.content.ai

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * DeepSeek chat-completions provider (Phase 7.2). Active only when
 * `app.ai.enabled=true`. This is the only class that knows the DeepSeek wire
 * format: it asks for strict JSON, extracts the assistant content and usage
 * counters, and maps the JSON into a [GenerationResult]. It is dependency-free
 * (JDK [HttpClient]) so the runtime classpath stays Spring-Boot-starters only.
 */
@Component
@ConditionalOnProperty(name = ["app.ai.enabled"], havingValue = "true")
class DeepSeekContentGenerationProvider(
    private val properties: AppAiProperties,
    private val mapper: ObjectMapper,
) : ContentGenerationProvider {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    override val name: String = "deepseek"

    override fun generate(request: GenerationRequest): GenerationResult {
        val apiKey = properties.deepseek.apiKey.trim()
        if (apiKey.isEmpty()) {
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "app.ai.deepseek.api-key is not configured")
        }
        val model = properties.deepseek.model.trim().ifEmpty { DEFAULT_MODEL }
        val url = properties.deepseek.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val body = mapper.writeValueAsString(chatRequest(model, request))
        val httpRequest = HttpRequest.newBuilder(URI(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = try {
            client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (ex: Exception) {
            log.warn("DeepSeek request failed: {}", ex.message)
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "DeepSeek request failed: " + ex.message)
        }
        if (response.statusCode() !in 200..299) {
            log.warn("DeepSeek returned {}: {}", response.statusCode(), response.body())
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "DeepSeek returned " + response.statusCode())
        }
        return parse(response.body(), model)
    }

    // --------------------------------------------------------------- wire format

    private fun chatRequest(model: String, request: GenerationRequest): Map<String, Any?> = linkedMapOf(
        "model" to model,
        "messages" to listOf(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT),
            mapOf("role" to "user", "content" to userPrompt(request)),
        ),
        "temperature" to 0.2,
        "response_format" to mapOf("type" to "json_object"),
        "stream" to false,
    )

    private fun userPrompt(request: GenerationRequest): String = buildString {
        appendLine("Generate a " + request.taskType + " task for a learner.")
        request.taskTypeLabel?.let { appendLine("Task label: " + it) }
        request.conceptCode?.let { appendLine("Concept code: " + it) }
        request.conceptName?.let { appendLine("Concept name: " + it) }
        appendLine("Subject: " + request.subject)
        appendLine("Grade level: " + request.gradeLevel)
        appendLine("Language: " + request.language)
        appendLine("Standard version: " + request.standardVersion)
        request.difficulty?.let { appendLine("Difficulty (1-5): " + it) }
        request.notes?.let { appendLine("Notes: " + it) }
        appendLine("Return only the JSON object described by the system message.")
    }

    private fun parse(body: String, fallbackModel: String): GenerationResult {
        val root = mapper.readTree(body)
        val choices = root.get("choices")?.takeIf { it.isArray && it.size() > 0 }
            ?: throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "DeepSeek response has no choices")
        val content = choices.get(0).get("message")?.get("content")?.asString()
            ?: throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "DeepSeek response has no message content")
        val usage = root.get("usage")
        val promptTokens = usage?.get("prompt_tokens")?.intValue() ?: 0
        val completionTokens = usage?.get("completion_tokens")?.intValue() ?: 0
        val model = root.get("model")?.asString()?.takeIf { it.isNotBlank() } ?: fallbackModel
        val parsed = try {
            mapper.readValue(content, GenerationResult::class.java)
        } catch (ex: Exception) {
            log.warn("DeepSeek returned malformed content JSON: {}", ex.message)
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "DeepSeek returned malformed JSON: " + ex.message)
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
        val SYSTEM_PROMPT = """
            You are a curriculum-aligned content generator. Reply with a single
            strict JSON object and nothing else. The schema is:
            {
              "body": string,
              "steps": [ { "orderIndex": int, "title": string, "body": string, "figureSvg": string|null } ],
              "questions": [ {
                "orderIndex": int,
                "stepIndex": int|null,
                "type": "MULTIPLE_CHOICE"|"TRUE_FALSE"|"SHORT_ANSWER"|"MATCHING"|"ESSAY",
                "text": string,
                "options": [string]|null,
                "correctAnswer": string|null,
                "explanation": string|null,
                "points": int,
                "difficulty": int,
                "matchingPairs": { "left": "right" }|null
              } ],
              "confidence": number,
              "sourceUrls": [string],
              "license": string|null
            }
            Never include markdown fences or commentary.
        """.trimIndent()
    }
}
