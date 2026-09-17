package com.afrithecus.brainbox.api.content.mcp

import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.content.AppContentProperties
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Phase 7 agent tool layer behind the MCP client: the DB metric query, the
 * validator-suite tool and the grounded, allow-listed web fetch. Web fetch is
 * enabled here with a single allow-listed domain and exercised against hosts that
 * never touch the network.
 */
@SpringBootTest(
    properties = [
        "app.content.tools.web-fetch.enabled=true",
        "app.content.tools.web-fetch.allowed-domains[0]=example.com",
    ],
)
@ActiveProfiles("test")
@Transactional
class ContentToolsTests(
    @Autowired private val tools: McpToolClient,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val clock: Clock,
    @Autowired private val contentUnits: ContentUnitRepository,
) {

    @Test
    fun registersTheAgentTools() {
        val names = tools.names()
        assertTrue(
            names.containsAll(setOf("concept_lookup", "metric_query", "validate_content", "web_fetch")),
            names.toString(),
        )
    }

    @Test
    fun metricQueryReadsCoverageAndQueue() {
        val coverage = tools.invoke("metric_query", mapper.readTree("{\"name\":\"coverage\"}"))
        assertTrue(coverage.has("overall"), coverage.toString())

        val queue = tools.invoke("metric_query", mapper.readTree("{\"name\":\"queue\"}"))
        assertTrue(queue.has("depth"), queue.toString())

        assertFailsWith<ApiException> { tools.invoke("metric_query", mapper.readTree("{\"name\":\"nope\"}")) }
    }

    @Test
    fun validateContentRunsTheValidatorChain() {
        val unit = contentUnits.save(
            ContentUnitEntity().apply {
                generationKey = "test:tools:" + UUID.randomUUID()
                taskType = "NOTES"
                title = "Tools unit"
                subject = "Mathematics"
                gradeLevel = "Grade 4"
                language = "en"
                body = "A short body for the validator chain."
                provenance = "GENERATED"
            },
        )
        val report = tools.invoke(
            "validate_content",
            mapper.readTree("{\"contentType\":\"UNIT\",\"contentId\":\"" + unit.id + "\"}"),
        )
        assertTrue(report.has("score"), report.toString())
        assertFailsWith<ApiException> { tools.invoke("validate_content", mapper.readTree("{}")) }
    }

    @Test
    fun webFetchIsAllowListedAndSsrfSafe() {
        // Restricted curriculum bodies, non-allow-listed hosts, private hosts and a
        // host that does not resolve are all refused before any request is made.
        assertFailsWith<ApiException> { tools.invoke("web_fetch", mapper.readTree("{\"url\":\"http://kicd.ac.ke/x\"}")) }
        assertFailsWith<ApiException> { tools.invoke("web_fetch", mapper.readTree("{\"url\":\"http://evil.example/x\"}")) }
        assertFailsWith<ApiException> { tools.invoke("web_fetch", mapper.readTree("{\"url\":\"http://127.0.0.1/x\"}")) }
        assertFailsWith<ApiException> {
            tools.invoke("web_fetch", mapper.readTree("{\"url\":\"http://allowed.example.invalid/x\"}"))
        }

        // A disabled instance fails closed regardless of the URL.
        val disabled = WebFetchTool(AppContentProperties(), mapper, clock)
        assertFailsWith<ApiException> {
            disabled.invoke(mapper.readTree("{\"url\":\"http://example.com/\"}"))
        }
    }
}
