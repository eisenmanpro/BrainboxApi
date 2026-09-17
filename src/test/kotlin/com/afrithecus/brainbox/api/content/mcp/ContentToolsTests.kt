package com.afrithecus.brainbox.api.content.mcp

import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Phase 7 agent tool layer behind the MCP client: the concept lookup (now
 * attaching the curriculum mapping), the DB metric query and the validator-suite
 * tool.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContentToolsTests(
    @Autowired private val tools: McpToolClient,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
) {

    @Test
    fun registersTheAgentTools() {
        val names = tools.names()
        assertTrue(
            names.containsAll(setOf("concept_lookup", "metric_query", "validate_content")),
            names.toString(),
        )
    }

    @Test
    fun conceptLookupByCodeAttachesTheCurriculumMapping() {
        val parent = concepts.save(
            ConceptEntity().apply {
                code = "TOOLS-STR-" + UUID.randomUUID().toString().take(6)
                name = "Tools strand"
                subject = "Mathematics"
                sortOrder = 0
            },
        )
        val concept = concepts.save(
            ConceptEntity().apply {
                code = "TOOLS-TOP-" + UUID.randomUUID().toString().take(6)
                name = "Tools topic"
                subject = "Mathematics"
                parentId = parent.id
                sortOrder = 0
            },
        )
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = "Grade 4"
                strandName = "Numbers"
                substrandName = "Fractions"
                learningOutcome = "Represent simple fractions as parts of a whole."
                sortOrder = 0
            },
        )

        val node = tools.invoke(
            "concept_lookup",
            mapper.readTree("{\"code\":\"" + concept.code + "\",\"gradeLevel\":\"Grade 4\"}"),
        )
        assertEquals("Numbers", node.get("strandName").asString())
        assertEquals("Fractions", node.get("substrandName").asString())
        assertEquals("Represent simple fractions as parts of a whole.", node.get("learningOutcome").asString())
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
}
