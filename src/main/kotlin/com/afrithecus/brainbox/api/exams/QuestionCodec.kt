package com.afrithecus.brainbox.api.exams

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** JSON (de)serialization for exam question option/matching-pair columns. */
@Component
class QuestionCodec(private val objectMapper: ObjectMapper) {

    fun toJson(list: List<String>?): String? = list?.let { objectMapper.writeValueAsString(it) }

    fun toJson(pairs: Map<String, String>?): String? = pairs?.let { objectMapper.writeValueAsString(it) }

    fun parseList(json: String?): List<String>? {
        if (json.isNullOrBlank()) return null
        val node = objectMapper.readTree(json)
        return (0 until node.size()).map { node.get(it).asString() }
    }

    fun parseMap(json: String?): Map<String, String>? {
        if (json.isNullOrBlank()) return null
        val node = objectMapper.readTree(json)
        val out = LinkedHashMap<String, String>()
        for (entry in node.properties()) {
            out[entry.key] = entry.value.asString()
        }
        return out
    }
}
