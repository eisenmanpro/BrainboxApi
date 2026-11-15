package com.afrithecus.brainbox.api.traditional

import com.afrithecus.brainbox.api.traditional.TraditionalSubjectTeacherAttribution.Assignment
import com.afrithecus.brainbox.api.traditional.TraditionalSubjectTeacherAttribution.LearnerRow
import com.afrithecus.brainbox.api.traditional.TraditionalSubjectTeacherAttribution.SubjectPanel
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for the §2.5I contribution attribution (D13/D14) and its honesty rules:
 * Unassigned bucket, Former teacher label, co-teaching "(shared)", alias matching, and the
 * reconciliation between the weighted bars and the page-1 subject mean.
 */
class TraditionalSubjectTeacherAttributionTests {

    private fun teacher(name: String): Assignment = Assignment(
        classTag = "",
        subjectKey = "",
        teacherUserId = UUID.nameUUIDFromBytes(name.toByteArray()),
        teacherName = name,
        former = false,
    )

    private fun assignment(classTag: String, subject: String, teacher: String): Assignment =
        teacher(teacher).copy(
            classTag = classTag,
            subjectKey = TraditionalSubjectTeacherAttribution.normalizeSubject(subject),
        )

    private fun panel(subjectId: String, name: String, gradeMean: Double, components: List<String> = emptyList()) =
        SubjectPanel(
            subjectId = subjectId,
            subjectName = name,
            matchKeys = TraditionalSubjectTeacherAttribution.subjectMatchKeys(name, components),
            gradeMean = gradeMean,
        )

    private fun row(classTag: String, kis: Double, math: Double) =
        LearnerRow(classTag, mapOf("KIS" to kis, "MATH" to math))

    /** Five classes, two learners each; the Kiswahili marks differ per class. */
    private val fiveClasses = listOf(
        "Grade 4 Lion" to listOf(80.0, 90.0),
        "Grade 4 Buffalo" to listOf(70.0, 60.0),
        "Grade 4 Elephant" to listOf(50.0, 40.0),
        "Grade 4 Rhino" to listOf(30.0, 20.0),
        "Grade 4 Leopard" to listOf(10.0, 0.0),
    )

    private val fiveKiswahiliTeachers = fiveClasses.mapIndexed { index, (classTag, _) ->
        assignment(classTag, "Kiswahili", "Kiswahili Teacher " + (index + 1))
    }

    private fun fiveClassRows(): List<LearnerRow> = fiveClasses.flatMap { (classTag, kisMarks) ->
        kisMarks.map { kis -> row(classTag, kis, 0.0) }
    }

    @Test
    fun `five subject teachers produce five bars, one per class teacher`() {
        val panels = TraditionalSubjectTeacherAttribution.build(
            subjects = listOf(panel("KIS", "Kiswahili", 45.0)),
            rows = fiveClassRows(),
            assignments = fiveKiswahiliTeachers,
        )
        val bars = panels.single().teachers
        assertEquals(5, bars.size)
        assertEquals(listOf(85.0, 65.0, 45.0, 25.0, 5.0), bars.map { it.mean })
        assertEquals(listOf(2, 2, 2, 2, 2), bars.map { it.learners })
        assertEquals("Grade 4 Lion", bars.first { it.mean == 85.0 }.classTags.single())
        assertTrue(bars.none { it.shared || it.former })
        assertNull(panels.single().unassigned)
        assertEquals(45.0, panels.single().gradeMean)
    }

    @Test
    fun `bars reconcile with the page-1 subject mean, rounding aside`() {
        val panels = TraditionalSubjectTeacherAttribution.build(
            subjects = listOf(panel("KIS", "Kiswahili", 45.0)),
            rows = fiveClassRows(),
            assignments = fiveKiswahiliTeachers,
        )
        val bars = panels.single().teachers
        val weighted = bars.sumOf { it.mean * it.learners } / bars.sumOf { it.learners }
        assertEquals(panels.single().gradeMean, weighted, 0.011)
        assertEquals(10, bars.sumOf { it.learners })
    }

    @Test
    fun `marks with no current assignment land in the Unassigned bucket`() {
        val panels = TraditionalSubjectTeacherAttribution.build(
            subjects = listOf(panel("KIS", "Kiswahili", 45.0), panel("MATH", "Mathematics", 50.0)),
            rows = fiveClassRows().map { it.copy(percentages = it.percentages + ("MATH" to 50.0)) },
            assignments = fiveKiswahiliTeachers,
        )
        val math = panels.first { it.subjectName == "Mathematics" }
        assertTrue(math.teachers.isEmpty())
        val unassigned = math.unassigned ?: error("the panel must account for every mark")
        assertEquals(10, unassigned.learners)
        assertEquals(50.0, unassigned.mean)
        assertTrue(panels.first { it.subjectName == "Kiswahili" }.unassigned == null)
    }

    @Test
    fun `a deactivated or missing teacher is labelled Former teacher, never a UUID`() {
        val gone = Assignment(
            classTag = "Grade 4 Lion",
            subjectKey = TraditionalSubjectTeacherAttribution.normalizeSubject("Kiswahili"),
            teacherUserId = null,
            teacherName = TraditionalSubjectTeacherAttribution.FORMER_TEACHER,
            former = true,
        )
        val bars = TraditionalSubjectTeacherAttribution.build(
            subjects = listOf(panel("KIS", "Kiswahili", 45.0)),
            rows = fiveClassRows().filter { it.classTag == "Grade 4 Lion" },
            assignments = listOf(gone),
        ).single().teachers
        assertEquals(1, bars.size)
        assertEquals(TraditionalSubjectTeacherAttribution.FORMER_TEACHER, bars.single().teacherName)
        assertNull(bars.single().teacherUserId)
        assertTrue(bars.single().former)
    }

    @Test
    fun `co-teaching gives both teachers the same marks and a shared flag instead of a split`() {
        val coTeachers = listOf(
            assignment("Grade 4 Lion", "Kiswahili", "Teacher A"),
            assignment("Grade 4 Lion", "Kiswahili", "Teacher B"),
        )
        val panels = TraditionalSubjectTeacherAttribution.build(
            subjects = listOf(panel("KIS", "Kiswahili", 45.0)),
            rows = fiveClassRows().filter { it.classTag == "Grade 4 Lion" },
            assignments = coTeachers,
        )
        val bars = panels.single().teachers
        assertEquals(2, bars.size)
        assertTrue(bars.all { it.shared && it.learners == 2 && it.mean == 85.0 })
        assertNull(panels.single().unassigned)
    }

    @Test
    fun `duplicate assignment rows for one teacher do not double count a learner`() {
        val duplicate = fiveKiswahiliTeachers + assignment("Grade 4 Lion", "Kiswahili", "Kiswahili Teacher 1")
        val bars = TraditionalSubjectTeacherAttribution.build(
            subjects = listOf(panel("KIS", "Kiswahili", 45.0)),
            rows = fiveClassRows(),
            assignments = duplicate,
        ).single().teachers
        assertEquals(5, bars.size)
        assertEquals(2, bars.first { it.classTags.single() == "Grade 4 Lion" }.learners)
    }

    @Test
    fun `school catalogue short codes match the exam subject names`() {
        val assignments = listOf(
            assignment("Grade 4 Lion", "KISW", "Kiswahili Teacher"),
            assignment("Grade 4 Lion", "MAT", "Mathematics Teacher"),
        )
        val panels = TraditionalSubjectTeacherAttribution.build(
            subjects = listOf(panel("KIS", "Kiswahili", 85.0), panel("MATH", "Mathematics", 85.0)),
            rows = fiveClassRows().filter { it.classTag == "Grade 4 Lion" }
                .map { it.copy(percentages = it.percentages + ("MATH" to 85.0)) },
            assignments = assignments,
        )
        assertEquals("Kiswahili Teacher", panels.first { it.subjectName == "Kiswahili" }.teachers.single().teacherName)
        assertEquals("Mathematics Teacher", panels.first { it.subjectName == "Mathematics" }.teachers.single().teacherName)
    }

    @Test
    fun `a combined subject also matches its component names`() {
        val assignments = listOf(assignment("Grade 4 Lion", "Composition", "English Teacher"))
        val panels = TraditionalSubjectTeacherAttribution.build(
            subjects = listOf(panel("ENG", "English", 85.0, components = listOf("Composition", "Insha"))),
            rows = listOf(LearnerRow("Grade 4 Lion", mapOf("ENG" to 85.0))),
            assignments = assignments,
        )
        assertEquals("English Teacher", panels.single().teachers.single().teacherName)
    }

    @Test
    fun `normalisation ignores case, punctuation and spacing and applies aliases`() {
        assertEquals(
            TraditionalSubjectTeacherAttribution.normalizeSubject("  KISWAHILI. "),
            TraditionalSubjectTeacherAttribution.normalizeSubject("kisw"),
        )
        assertEquals(
            TraditionalSubjectTeacherAttribution.normalizeSubject("Pre-Technical Studies"),
            TraditionalSubjectTeacherAttribution.normalizeSubject("pretech"),
        )
        assertEquals(
            TraditionalSubjectTeacherAttribution.normalizeSubject("Science & Technology"),
            TraditionalSubjectTeacherAttribution.normalizeSubject("SCI"),
        )
    }
}
