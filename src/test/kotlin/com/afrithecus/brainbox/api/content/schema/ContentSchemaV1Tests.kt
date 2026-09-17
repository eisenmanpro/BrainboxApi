package com.afrithecus.brainbox.api.content.schema

import com.afrithecus.brainbox.api.content.validation.FindingSeverity
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The versioned content JSON contract: a well-shaped response passes, a structural
 * violation is a BLOCKER (so the provider call fails before caching), and unknown
 * fields are informational drift only.
 */
class ContentSchemaV1Tests {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun acceptsAValidV1Response() {
        val node = mapper.readTree(
            """
            {
              "body": "Intro",
              "steps": [ { "orderIndex": 0, "title": "One", "body": "Body", "figure": null } ],
              "questions": [ { "orderIndex": 0, "type": "MULTIPLE_CHOICE", "text": "2+2?", "options": ["3", "4"], "correctAnswer": "4" } ],
              "confidence": 0.9,
              "sourceUrls": [],
              "license": null
            }
            """.trimIndent()
        )
        assertTrue(
            ContentSchemaV1.validate(node).none { it.severity == FindingSeverity.BLOCKER },
            ContentSchemaV1.validate(node).toString(),
        )
    }

    @Test
    fun validatesFigureSpecs() {
        assertTrue(
            ContentSchemaV1.validate(
                mapper.readTree("{\"steps\":[{\"orderIndex\":0,\"figure\":{\"kind\":\"BAR\",\"categories\":[\"A\"],\"values\":[1]}}]}"),
            ).none { it.severity == FindingSeverity.BLOCKER },
        )

        val violations = ContentSchemaV1.validate(
            mapper.readTree("{\"questions\":[{\"type\":\"ESSAY\",\"text\":\"x\",\"figure\":{\"kind\":\"PIE\"}}]}"),
        )
        assertTrue(
            violations.any { it.code == "FIGURE_KIND_INVALID" && it.severity == FindingSeverity.BLOCKER },
            violations.toString(),
        )
        assertTrue(violations.any { it.message.contains("questions[0].figure") }, violations.toString())

        assertTrue(
            ContentSchemaV1.validate(mapper.readTree("{\"steps\":[{\"orderIndex\":0,\"figure\":\"nope\"}]}"))
                .any { it.code == "SCHEMA_TYPE_MISMATCH" },
        )
    }

    @Test
    fun reportsTheContractVersion() {
        assertEquals("ke-cbc-content-v1", ContentSchemaV1.VERSION)
        assertTrue(ContentSchemaV1.SUPPORTED.contains(ContentSchemaV1.VERSION))
    }

    @Test
    fun blocksStructuralViolations() {
        assertEquals("SCHEMA_ROOT_NOT_OBJECT", firstBlocker(mapper.readTree("[1, 2, 3]")).code)
        assertEquals("SCHEMA_TYPE_MISMATCH", firstBlocker(mapper.readTree("{\"steps\":\"nope\"}")).code)
        assertEquals(
            "SCHEMA_QUESTION_TYPE_INVALID",
            firstBlocker(mapper.readTree("{\"questions\":[{\"type\":\"WORD_SEARCH\",\"text\":\"x\"}]}")).code,
        )
        assertEquals(
            "SCHEMA_QUESTION_TEXT_MISSING",
            firstBlocker(mapper.readTree("{\"questions\":[{\"type\":\"ESSAY\"}]}")).code,
        )
        assertEquals(
            "SCHEMA_OPTIONS_INVALID",
            firstBlocker(mapper.readTree("{\"questions\":[{\"type\":\"MULTIPLE_CHOICE\",\"text\":\"x\",\"options\":[1,2]}]}")).code,
        )
    }

    @Test
    fun unknownFieldsAreInformationalOnly() {
        val violations = ContentSchemaV1.validate(
            mapper.readTree("{\"body\":\"b\",\"steps\":[],\"questions\":[],\"mystery\":true}"),
        )
        assertTrue(violations.none { it.severity == FindingSeverity.BLOCKER }, violations.toString())
        assertTrue(violations.any { it.code == "SCHEMA_UNKNOWN_FIELD" }, violations.toString())
    }

    private fun firstBlocker(node: JsonNode): SchemaViolation =
        ContentSchemaV1.validate(node).first { it.severity == FindingSeverity.BLOCKER }
}
