package com.afrithecus.brainbox.api.traditional

import com.afrithecus.brainbox.api.traditional.web.SubjectTeacherContributionDto
import com.afrithecus.brainbox.api.traditional.web.TeacherContributionDto
import com.afrithecus.brainbox.api.traditional.web.UnassignedContributionDto
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Subject-teacher attribution for the analysis' contribution page (D13/D14, §2.5I).
 *
 * The page answers "which subject teacher contributed what", which the mark rows
 * cannot answer directly: a mark records only who keyed it, not who taught the
 * subject. Attribution therefore comes from the **current** `teacher_classes`
 * assignments (there is no historical snapshot — F37):
 *
 *  - a mark for (class, subject) belongs to every teacher assignment with the same
 *    class name, the same grade and a normalized-equal subject in the same school;
 *  - unmatched marks land in the MUTED "Unassigned" bucket so the panel accounts for
 *    every mark behind the page-1 mean;
 *  - a missing/deactivated teacher account is labelled "Former teacher", never a UUID;
 *  - co-teaching (two teachers on the same class+subject) gives each teacher the same
 *    marks and a "(shared)" flag — decided, not split, so no attribution is invented.
 *
 * The pure builder is separated from the database loading so the rules are unit
 * tested without Spring; the service batch-loads assignments (one class query, one
 * user query) and never issues a query per bar.
 */
internal object TraditionalSubjectTeacherAttribution {

    /** One subject panel, in exam subject order. */
    data class SubjectPanel(
        val subjectId: String,
        val subjectName: String,
        /** Normalized subject name plus its component names, for the assignment match. */
        val matchKeys: Set<String>,
        /** Page-1 subject percentage mean over the same complete learners. */
        val gradeMean: Double,
    )

    /** One complete learner row: their class and their subject percentages by subject id. */
    data class LearnerRow(
        val classTag: String,
        val percentages: Map<String, Double>,
    )

    /** One current `teacher_classes` row resolved to a teacher account. */
    data class Assignment(
        val classTag: String,
        /** Normalized subject of the teaching assignment. */
        val subjectKey: String,
        val teacherUserId: UUID?,
        val teacherName: String,
        val former: Boolean,
    )

    /** Display label for an assignment whose teacher account is gone or deactivated. */
    const val FORMER_TEACHER = "Former teacher"

    // ------------------------------------------------------------------ rules

    private val WHITESPACE = Regex("[^a-z0-9]+")

    /**
     * Normalized comparison key: trim, lowercase, spell "&" as "and", collapse every
     * non-alphanumeric run to one space. "KISWAHILI", " kiswahili " and "Kiswahili." all
     * match; so do "Pre-Technical Studies" and "pre technical studies", and
     * "Science & Technology" matches the catalogue's "Science and Technology".
     */
    fun normalizeKey(raw: String?): String =
        raw.orEmpty().lowercase(Locale.ROOT).replace("&", " and ").replace(WHITESPACE, " ").trim()

    /**
     * Documented alias map for the school catalogue's short codes (the school writes
     * "KISW"/"MAT" where the exam subject is "Kiswahili"/"Mathematics"). Keys and
     * values are normalized through [normalizeKey] at construction so the map matches
     * whatever punctuation/case the two sources use.
     */
    private val SUBJECT_ALIASES: Map<String, String> = buildMap {
        val aliases = mapOf(
            "eng" to "english",
            "kis" to "kiswahili",
            "kisw" to "kiswahili",
            "mat" to "mathematics",
            "math" to "mathematics",
            "maths" to "mathematics",
            "env" to "environmental activities",
            "sci" to "science and technology",
            "int" to "integrated science",
            "intsci" to "integrated science",
            "soc" to "social studies",
            "ss" to "social studies",
            "sst" to "social studies",
            "hsc" to "home science",
            "agr" to "agriculture",
            "agri" to "agriculture",
            "re" to "religious education",
            "cre" to "religious education",
            "ire" to "religious education",
            "cat" to "creative arts",
            "cas" to "creative arts and sports",
            "phe" to "physical and health education",
            "bus" to "business studies",
            "pretech" to "pre-technical studies",
        )
        aliases.forEach { (short, full) -> put(normalizeKey(short), normalizeKey(full)) }
    }

    /** Normalized subject key with the documented aliases applied. */
    fun normalizeSubject(raw: String?): String {
        val key = normalizeKey(raw)
        return SUBJECT_ALIASES[key] ?: key
    }

    /** The match keys for an exam subject: its own name plus any combined-subject components. */
    fun subjectMatchKeys(name: String?, componentNames: List<String> = emptyList()): Set<String> =
        (listOfNotNull(name) + componentNames).map(::normalizeSubject).filter { it.isNotEmpty() }.toSet()

    // ----------------------------------------------------------------- builder

    private class Bucket(val assignment: Assignment) {
        var sum: Double = 0.0
        var learners: Int = 0
        var shared: Boolean = false
        val classTags = linkedSetOf<String>()

        fun mean(): Double = if (learners > 0) sum / learners else 0.0

        fun add(percentage: Double, classTag: String, sharedRow: Boolean) {
            sum += percentage
            learners += 1
            shared = shared || sharedRow
            classTag.trim().takeIf { it.isNotEmpty() }?.let { classTags.add(it) }
        }

        fun toDto(): TeacherContributionDto = TeacherContributionDto(
            teacherUserId = assignment.teacherUserId?.toString(),
            teacherName = assignment.teacherName,
            classTags = classTags.sorted(),
            learners = learners,
            mean = round2(mean()),
            shared = shared,
            former = assignment.former,
        )
    }

    /** Stable teacher identity: the account id, or the display name when the account is gone. */
    private fun teacherKey(assignment: Assignment): String =
        assignment.teacherUserId?.toString() ?: "name:" + normalizeKey(assignment.teacherName)

    /**
     * Builds one panel per subject in exam order. Only [rows] (the complete learners used
     * for the page-1 means) feed the bars, so the bars reconcile with the page-1 subject
     * mean — weighted by learner count, rounding aside (left to the caller to assert).
     */
    fun build(
        subjects: List<SubjectPanel>,
        rows: List<LearnerRow>,
        assignments: List<Assignment>,
    ): List<SubjectTeacherContributionDto> = subjects.map { panel ->
        val matching = assignments.filter { it.subjectKey in panel.matchKeys }
        val byClass = matching.groupBy { normalizeKey(it.classTag) }
        val buckets = LinkedHashMap<String, Bucket>()
        var unassignedLearners = 0
        var unassignedSum = 0.0
        rows.forEach { row ->
            val percentage = row.percentages[panel.subjectId] ?: return@forEach
            val teachers = byClass[normalizeKey(row.classTag)].orEmpty()
                .distinctBy { teacherKey(it) }
            if (teachers.isEmpty()) {
                unassignedLearners += 1
                unassignedSum += percentage
                return@forEach
            }
            // Two teachers on the same class+subject are co-teachers: each gets the same
            // marks and a shared flag instead of an invented split (decision 11).
            val shared = teachers.size > 1
            teachers.forEach { assignment ->
                buckets.getOrPut(teacherKey(assignment)) { Bucket(assignment) }
                    .add(percentage, row.classTag, shared)
            }
        }
        SubjectTeacherContributionDto(
            subjectId = panel.subjectId,
            subjectName = panel.subjectName,
            gradeMean = round2(panel.gradeMean),
            teachers = buckets.values
                .filter { it.learners > 0 }
                .sortedWith(compareByDescending<Bucket> { it.mean() }.thenBy { it.assignment.teacherName })
                .map { it.toDto() },
            unassigned = if (unassignedLearners > 0) {
                UnassignedContributionDto(unassignedLearners, round2(unassignedSum / unassignedLearners))
            } else {
                null
            },
        )
    }

    private fun round2(value: Double): Double = (value * 100).roundToInt() / 100.0
}
