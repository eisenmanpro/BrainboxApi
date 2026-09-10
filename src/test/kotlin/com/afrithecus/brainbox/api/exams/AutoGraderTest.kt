package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.model.QuestionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class AutoGraderTest {

    private val mapper = JsonMapper.builder().build()
    private val grader = AutoGrader(mapper)

    private fun question(
        type: QuestionType,
        correctAnswer: String? = null,
        matchingPairs: String? = null,
        points: Int = 1,
    ) = ExamQuestionEntity().apply {
        qType = type
        this.correctAnswer = correctAnswer
        this.matchingPairs = matchingPairs
        this.points = points
    }

    private fun node(value: Any?) = mapper.readTree(mapper.writeValueAsString(value))

    @Test
    fun `mcq and number questions compare normalized text`() {
        val q = question(QuestionType.MCQ, correctAnswer = "4")
        assertTrue(grader.grade(q, node("4")).isCorrect)
        assertTrue(grader.grade(q, node(" 4 ")).isCorrect)
        assertFalse(grader.grade(q, node("5")).isCorrect)
        assertFalse(grader.grade(q, null).isCorrect)

        val number = question(QuestionType.NUMBER_ENTRY, correctAnswer = "3.5")
        assertTrue(grader.grade(number, node("3.5")).isCorrect)
        assertFalse(grader.grade(number, node("3.6")).isCorrect)
    }

    @Test
    fun `multi select requires the exact set`() {
        val q = question(QuestionType.MULTI_SELECT, correctAnswer = """["A","C"]""")
        assertTrue(grader.grade(q, node(listOf("A", "C"))).isCorrect)
        assertFalse(grader.grade(q, node(listOf("A"))).isCorrect)
        assertFalse(grader.grade(q, node(listOf("A", "B", "C"))).isCorrect)
    }

    @Test
    fun `matching compares full pairs map`() {
        val q = question(QuestionType.MATCHING, matchingPairs = """{"a":"1","b":"2"}""")
        assertTrue(grader.grade(q, node(mapOf("a" to "1", "b" to "2"))).isCorrect)
        assertFalse(grader.grade(q, node(mapOf("a" to "1"))).isCorrect)
        assertFalse(grader.grade(q, node(mapOf("a" to "9", "b" to "2"))).isCorrect)
    }

    @Test
    fun `short answer normalizes case and whitespace`() {
        val q = question(QuestionType.SHORT_ANSWER, correctAnswer = "Lagos")
        assertTrue(grader.grade(q, node("lagos")).isCorrect)
        assertTrue(grader.grade(q, node("  Lagos  ")).isCorrect)
        // internal runs of whitespace collapse (regression: regex once matched a literal "s+")
        val city = question(QuestionType.SHORT_ANSWER, correctAnswer = "New York")
        assertTrue(grader.grade(city, node("new   york")).isCorrect)
        assertFalse(grader.grade(city, node("newyork")).isCorrect)
    }

    @Test
    fun `essay is never auto marked correct`() {
        val q = question(QuestionType.ESSAY)
        val outcome = grader.grade(q, node("some long essay"))
        assertFalse(outcome.isCorrect)
        assertEquals(0, outcome.pointsEarned)
    }
}
