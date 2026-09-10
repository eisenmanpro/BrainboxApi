package com.afrithecus.brainbox.api.interview

import com.afrithecus.brainbox.api.interview.entity.InterviewQuestionEntity
import com.afrithecus.brainbox.api.interview.model.InterviewMode
import com.afrithecus.brainbox.api.interview.model.InterviewRubricType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** A scored rubric criterion (STAR/SOAR/SHARE). */
data class RubricCriterionScore(val name: String, val score: Int, val maxScore: Int, val feedback: String)

data class RubricResult(val type: InterviewRubricType, val criteria: List<RubricCriterionScore>, val overallScore: Int)

/** Fully server-computed answer evaluation. */
data class AnswerEvaluation(
    val wordCount: Int,
    val clarityScore: Int,
    val fillerWordCount: Int,
    val fillerReplacements: Map<String, String>,
    val pace: Int,
    val keywordMatchCount: Int,
    val totalKeywords: Int,
    val structurePhrasesFound: Int,
    val feedback: String,
    val score: Int,
    val rubric: RubricResult?,
    val dictionScore: Int,
    val pronunciationScore: Int,
)

/**
 * Server-authoritative interview scoring. Mirrors the algorithm the Android app
 * previously ran in its mock so the numbers stay stable now that the server owns
 * them: keyword coverage, length, filler words, structure phrases and the
 * STAR/SOAR/SHARE rubric.
 */
@Component
class InterviewScoringEngine(private val mapper: ObjectMapper) {

    /** Keywords appended to a question when the session runs at a high difficulty. */
    fun advancedKeywords(): List<String> = ADVANCED_KEYWORDS

    fun evaluate(question: InterviewQuestionEntity, transcribedText: String, mode: InterviewMode, difficulty: Int = 1): AnswerEvaluation {
        val text = transcribedText.trim()
        val words = text.split(Regex("""\s+"""))
        val wordCount = words.size
        val advanced = difficulty > 1
        val keywords = parseList(question.keywords).let { if (advanced) it + ADVANCED_KEYWORDS else it }
        val minWords = question.minWords + if (advanced) 20 else 0
        val maxWords = question.maxWords
        val structurePhrases = parseList(question.structurePhrases).ifEmpty { DEFAULT_STRUCTURE_PHRASES }

        val matchedKeywords = keywords.count { text.contains(it, ignoreCase = true) }
        val keywordScore = if (keywords.isNotEmpty()) (matchedKeywords.toDouble() / keywords.size) * 40 else 0.0

        val lengthScore = when {
            wordCount in minWords..maxWords -> 20.0
            wordCount < minWords -> (wordCount.toDouble() / minWords) * 20
            else -> (maxWords.toDouble() / wordCount) * 20
        }

        var fillerCount = 0
        val replacementsFound = LinkedHashMap<String, String>()
        for (word in words) {
            val lower = word.lowercase().trim(',', '.', '!', '?', ';', ':')
            val replacement = FILLERS[lower]
            if (replacement != null) {
                fillerCount++
                replacementsFound[lower] = replacement
            }
        }
        val fillerScore = maxOf(0.0, 20.0 - fillerCount * 2)

        val structureCount = structurePhrases.count { text.contains(it, ignoreCase = true) }
        val structureScore = minOf(20.0, structureCount * 5.0)

        val complexWords = words.count { it.lowercase().trim(',', '.') in COMPLEX_WORDS }
        val dictionScore = (complexWords * 10).coerceIn(0, 100)
        // No audio confidence reaches the server (emotion/pronunciation run on-device),
        // so this is a transparent text-derived clarity proxy, penalising filler use.
        val pronunciationScore = (clarityBase(keywordScore, lengthScore) - minOf(25, fillerCount * 3)).coerceIn(0, 100)

        val rubric = if (question.rubricType != InterviewRubricType.NONE) {
            calculateRubric(question.rubricType, text)
        } else {
            null
        }

        val baseScore = (keywordScore + lengthScore + fillerScore + structureScore).toInt()
        val finalScore = if (rubric != null) {
            ((baseScore * 0.4) + (rubric.overallScore * 0.6)).toInt().coerceIn(0, 100)
        } else {
            baseScore.coerceIn(0, 100)
        }

        val feedback = buildFeedback(text, words.size, minWords, maxWords, keywords, matchedKeywords, fillerCount, structureCount, rubric)
        return AnswerEvaluation(
            wordCount = wordCount,
            clarityScore = (keywordScore + lengthScore).toInt().coerceIn(0, 100),
            fillerWordCount = fillerCount,
            fillerReplacements = replacementsFound,
            pace = if (mode != InterviewMode.Q_A) (wordCount * 60) / 15 else 0,
            keywordMatchCount = matchedKeywords,
            totalKeywords = keywords.size,
            structurePhrasesFound = structureCount,
            feedback = feedback,
            score = finalScore,
            rubric = rubric,
            dictionScore = dictionScore,
            pronunciationScore = pronunciationScore,
        )
    }

    private fun clarityBase(keywordScore: Double, lengthScore: Double): Int =
        ((keywordScore + lengthScore) / 60.0 * 100.0).toInt().coerceIn(0, 100)

    private fun buildFeedback(
        text: String,
        wordCount: Int,
        minWords: Int,
        maxWords: Int,
        keywords: List<String>,
        matchedKeywords: Int,
        fillerCount: Int,
        structureCount: Int,
        rubric: RubricResult?,
    ): String = buildString {
        if (matchedKeywords < keywords.size) {
            append("Add keywords: ")
            append(keywords.filterNot { text.contains(it, ignoreCase = true) }.take(3).joinToString())
            append(". ")
        }
        if (wordCount < minWords) {
            append("Answer too short (").append(wordCount).append(" words). Aim for ")
            append(minWords).append("-").append(maxWords).append(" words. ")
        } else if (wordCount > maxWords) {
            append("Answer too long (").append(wordCount).append(" words). Keep under ")
            append(maxWords).append(" words. ")
        }
        if (fillerCount > 3) append("Reduce filler words (").append(fillerCount).append(" filler words). ")
        if (structureCount < 1) append("Use structure phrases like 'firstly', 'secondly' to organize your answer. ")
        rubric?.criteria?.forEach { criterion ->
            if (criterion.score < 3) append(criterion.name).append(" could be stronger: ").append(criterion.feedback).append(" ")
        }
        if (isEmpty()) append("Good answer! Keep practicing.")
    }

    private fun calculateRubric(type: InterviewRubricType, text: String): RubricResult {
        val names = when (type) {
            InterviewRubricType.STAR -> listOf("Situation", "Task", "Action", "Result")
            InterviewRubricType.SOAR -> listOf("Situation", "Obstacle", "Action", "Result")
            InterviewRubricType.SHARE -> listOf("Situation", "Hardship", "Action", "Result", "Evaluation")
            InterviewRubricType.NONE -> emptyList()
        }
        val criteria = names.map { name ->
            val keywords = RUBRIC_KEYWORDS[name].orEmpty()
            val matched = keywords.count { text.contains(it, ignoreCase = true) }
            val score = when {
                matched >= 4 -> 5
                matched == 3 -> 4
                matched == 2 -> 3
                matched == 1 -> 2
                text.length > 300 -> 2
                else -> 1
            }
            val feedback = when (score) {
                5 -> "Excellent detail and specific keywords used."
                4 -> "Good coverage of the " + name + " component."
                3 -> "Adequate, but could use more specific action verbs or metrics."
                2 -> "Brief mention detected; expand on this section."
                else -> "Missing key elements of " + name + ". Be more descriptive."
            }
            RubricCriterionScore(name, score, 5, feedback)
        }
        val overall = if (criteria.isEmpty()) 0 else (criteria.map { it.score }.average() * 20).toInt()
        return RubricResult(type, criteria, overall)
    }

    private fun parseList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).map { node.get(it).asString() }
    }

    private companion object {
        val DEFAULT_STRUCTURE_PHRASES = listOf("firstly", "secondly", "finally", "in conclusion", "for example")

        val FILLERS = mapOf(
            "um" to "a silent pause",
            "uh" to "a silent pause",
            "like" to "specifically / for example",
            "basically" to "the core idea is",
            "actually" to "in fact",
            "you know" to "as you may be aware",
            "i mean" to "to clarify",
            "sort of" to "somewhat / to an extent",
        )

        val ADVANCED_KEYWORDS = listOf("strategic", "analytical", "leadership")

        val COMPLEX_WORDS = setOf(
            "implement", "coordinate", "facilitate", "leverage", "collaborate", "innovative", "strategic", "efficiency",
        )

        val RUBRIC_KEYWORDS = mapOf(
            "Situation" to listOf("when", "at", "during", "project", "time", "background", "context", "scenario", "event", "happened"),
            "Task" to listOf("goal", "task", "assignment", "objective", "required", "responsibility", "expected", "role", "aim", "duty"),
            "Action" to listOf("i did", "managed", "coordinated", "built", "implemented", "organized", "started", "developed", "led", "solved", "created", "executed", "handled"),
            "Result" to listOf("result", "outcome", "consequence", "achieved", "learned", "improved", "saved", "increased", "decreased", "impact", "delivered", "successful", "final"),
            "Obstacle" to listOf("difficult", "challenge", "hard", "problem", "blocked", "issue", "setback", "hindrance", "struggle", "conflict"),
            "Hardship" to listOf("struggle", "failure", "crisis", "pressure", "tough", "adversity", "ordeal", "dire", "severe"),
            "Evaluation" to listOf("feedback", "measured", "assessment", "reflection", "better", "learned", "evaluation", "review", "takeaway"),
        )
    }
}
