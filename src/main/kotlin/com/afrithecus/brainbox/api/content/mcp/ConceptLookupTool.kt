package com.afrithecus.brainbox.api.content.mcp

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * `concept_lookup`: resolves a concept either directly by `code` or by
 * `countryCode` + `curriculum` (optionally narrowed by `gradeLevel`,
 * `strandCode` or `strandName`). It grounds generation in the shared concept
 * catalogue from Phase 7.1.
 */
@Component
class ConceptLookupTool(
    private val concepts: ConceptRepository,
    private val curriculumMaps: CurriculumMapRepository,
    private val mapper: ObjectMapper,
) : McpTool {

    override val name: String = "concept_lookup"

    override fun invoke(payload: JsonNode): JsonNode {
        payload.get("code")?.asString()?.trim()?.takeIf { it.isNotEmpty() }?.let { code ->
            val concept = concepts.findByCode(code)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "concept not found for code '" + code + "'")
            return objectNode(concept, null)
        }

        val countryCode = required(payload, "countryCode")
        val curriculum = required(payload, "curriculum")
        val gradeLevel = optional(payload, "gradeLevel")
        val strandCode = optional(payload, "strandCode")
        val strandName = optional(payload, "strandName")

        val matches = curriculumMaps.findAllByCountryCodeAndCurriculum(countryCode, curriculum)
            .filter { gradeLevel == null || it.gradeLevel.equals(gradeLevel, ignoreCase = true) }
            .filter { strandCode == null || it.strandCode.equals(strandCode, ignoreCase = true) }
            .filter { strandName == null || it.strandName?.contains(strandName, ignoreCase = true) == true }

        if (matches.isEmpty()) {
            throw ApiException(
                ApiErrorCode.NOT_FOUND,
                "no concepts matched country=" + countryCode + ", curriculum=" + curriculum,
            )
        }

        val array = mapper.createArrayNode()
        matches.forEach { mapping ->
            concepts.findById(mapping.conceptId).ifPresent { array.add(objectNode(it, mapping)) }
        }
        return array
    }

    private fun required(payload: JsonNode, field: String): String =
        optional(payload, field) ?: throw invalidArgument("concept_lookup requires '" + field + "'")

    private fun optional(payload: JsonNode, field: String): String? =
        payload.get(field)?.asString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun objectNode(concept: ConceptEntity, mapping: CurriculumMapEntity?): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("id", concept.id.toString())
        node.put("code", concept.code)
        node.put("name", concept.name)
        node.put("subject", concept.subject)
        concept.description?.let { node.put("description", it) }
        if (mapping != null) {
            node.put("countryCode", mapping.countryCode)
            node.put("curriculum", mapping.curriculum)
            node.put("gradeLevel", mapping.gradeLevel)
            mapping.strandCode?.let { node.put("strandCode", it) }
            mapping.strandName?.let { node.put("strandName", it) }
            mapping.substrandCode?.let { node.put("substrandCode", it) }
            mapping.substrandName?.let { node.put("substrandName", it) }
            mapping.learningOutcome?.let { node.put("learningOutcome", it) }
        }
        return node
    }
}
