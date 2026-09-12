package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.model.QuestionType
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Result of grading a single question. */
data class GradeOutcome(
    val isCorrect: Boolean,
    val pointsEarned: Int,
)

/**
 * Server-side auto-grading (doc 02 §3.3). Students submit raw answers only; the
 * server compares against stored keys and is authoritative on scores. Essay
 * questions are not auto-gradable and earn zero until human review.
 */
@Component
class AutoGrader(private val mapper: ObjectMapper) {

    fun grade(question: ExamQuestionEntity, userValue: JsonNode?): GradeOutcome =
        grade(question.qType, resolvedKey(question), question.matchingPairs, question.points, userValue)

    /** Type-based grading shared by exams and contests (any keyed question shape). */
    fun grade(
        type: QuestionType,
        correctAnswer: String?,
        matchingPairs: String?,
        points: Int,
        userValue: JsonNode?,
    ): GradeOutcome {
        if (userValue == null || userValue.isMissingNode || userValue.isNull) {
            return GradeOutcome(isCorrect = false, pointsEarned = 0)
        }
        val correct = when (type) {
            QuestionType.MCQ,
            QuestionType.TRUE_FALSE,
            QuestionType.NUMBER_ENTRY,
            QuestionType.SHORT_ANSWER,
            QuestionType.FILL_BLANK,
            -> textEquals(userValue, correctAnswer)

            QuestionType.MULTI_SELECT -> multiSelectEquals(userValue, correctAnswer)

            QuestionType.MATCHING -> matchingEquals(userValue, matchingPairs)

            QuestionType.ESSAY -> false
        }
        return GradeOutcome(isCorrect = correct, pointsEarned = if (correct) points else 0)
    }

    fun isAutoGradable(type: QuestionType): Boolean = type != QuestionType.ESSAY

    private fun textEquals(userValue: JsonNode, correctRaw: String?): Boolean {
        if (correctRaw.isNullOrBlank() || !userValue.isValueNode) return false
        val user = normalize(userValue.asString())
        val expected = normalize(correctRaw)
        return user.isNotEmpty() && user == expected
    }

    /**
     * MCQ keys authored by the teacher app encode the correct option as
     * __IDX__n__ rather than its text; resolve it against the option list so
     * those exams grade exactly like admin-authored ones.
     */
    private fun resolvedKey(question: ExamQuestionEntity): String? {
        val raw = question.correctAnswer ?: return null
        if (question.qType != QuestionType.MCQ) return raw
        val index = mcqIndex(raw) ?: return raw
        return parseList(question.options)?.getOrNull(index) ?: raw
    }

    private fun mcqIndex(raw: String): Int? {
        if (!raw.startsWith(MCQ_INDEX_PREFIX) || !raw.endsWith(MCQ_INDEX_SUFFIX)) return null
        return raw.removePrefix(MCQ_INDEX_PREFIX).removeSuffix(MCQ_INDEX_SUFFIX).toIntOrNull()
    }

    private fun multiSelectEquals(userValue: JsonNode, correctRaw: String?): Boolean {
        val expected = parseSelection(correctRaw) ?: return false
        if (!userValue.isArray) return false
        val user = buildSet { userValue.forEach { add(normalize(it.asString())) } }
        val normalizedExpected = expected.map(::normalize).toSet()
        return user.isNotEmpty() && normalizedExpected.isNotEmpty() && user == normalizedExpected
    }

    private fun matchingEquals(userValue: JsonNode, pairsRaw: String?): Boolean {
        val expected = parseMap(pairsRaw) ?: return false
        if (!userValue.isObject || expected.isEmpty()) return false
        val userMap = LinkedHashMap<String, String>()
        for (entry in userValue.properties()) {
            userMap[entry.key] = normalize(entry.value.asString())
        }
        if (userMap.size != expected.size) return false
        return expected.all { (key, value) -> userMap[key] == normalize(value) }
    }

    private fun parseList(json: String?): List<String>? {
        if (json.isNullOrBlank()) return null
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        if (!node.isArray) return null
        val out = mutableListOf<String>()
        for (i in 0 until node.size()) out.add(node.get(i).asString())
        return out
    }

    /** Accepts a JSON array or a comma-separated list of selected options. */
    private fun parseSelection(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        parseList(raw)?.let { return it }
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }

    private fun parseMap(json: String?): Map<String, String>? {
        if (json.isNullOrBlank()) return null
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        if (!node.isObject) return null
        return buildMap {
            for (entry in node.properties()) put(entry.key, entry.value.asString())
        }
    }

    private fun normalize(value: String?): String =
        value?.trim()?.lowercase()?.replace(Regex("""\s+"""), " ") ?: ""

    private companion object {
        const val MCQ_INDEX_PREFIX = "__IDX__"
        const val MCQ_INDEX_SUFFIX = "__"
    }
}
