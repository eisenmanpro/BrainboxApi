package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.GradeAnalysisSpec
import com.afrithecus.brainbox.api.report.GradeTableSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportGradingBand
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.web.ClassGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.GradingBandDto
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDto
import com.afrithecus.brainbox.api.traditional.web.StudentGradeRowDto
import com.afrithecus.brainbox.api.traditional.web.SubjectConfigDto
import com.afrithecus.brainbox.api.traditional.web.SubjectTeacherContributionDto
import com.afrithecus.brainbox.api.traditional.web.TeacherContributionDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalSubjectResultDto
import com.afrithecus.brainbox.api.traditional.web.UnassignedContributionDto
import kotlin.math.roundToInt

/**
 * The scale fixture behind A17 and the A15 preview harness.
 *
 * 500 learners across five Grade 5 classes (100 each, 15 incomplete: one missing subject and two
 * whole-subject zeros per class), deliberate ties (every 41st learner scores exactly 60 % per
 * subject), long and accented names, and five class teachers. [scaleSpec] builds it at any subject
 * count from the decision-13 catalogue so the whole orientation matrix (5 portrait, 9 and 13
 * landscape) renders from the same numbers as the approved lab (template/proto L7).
 *
 * [analysis13] is the matching 13-subject grade-analysis payload with every class score position
 * unique, five teachers per subject, and the L6 contribution cases (a co-taught shared bar, a
 * former teacher, an unattributed class) chosen so the bars always reconcile with the page-1
 * class means: for every subject, Σ(bar.learners) equals the ranked learners behind the mean and
 * the learner-weighted bar mean equals the printed grade mean.
 */
object ReportScaleFixtures {

    const val CLASS_SIZE = 100
    const val INCOMPLETE_PER_CLASS = 3
    const val LEARNERS = CLASS_SIZE * 5

    val CLASS_TAGS = listOf(
        "Grade 5 Lion",
        "Grade 5 Buffalo",
        "Grade 5 Elephant",
        "Grade 5 Rhino",
        "Grade 5 Cheetah",
    )

    val CLASS_TEACHERS: Map<String, String> = linkedMapOf(
        "Grade 5 Lion" to "Mr. James Otieno",
        "Grade 5 Buffalo" to "Ms. Grace Wairimu",
        "Grade 5 Elephant" to "Mr. Peter Kamau",
        "Grade 5 Rhino" to "Ms. Faith Muthoni",
        "Grade 5 Cheetah" to "Mrs. Alice Njeri",
    )

    /** name to max score, in the decision-13 catalogue order. */
    val SUBJECTS: List<Pair<String, Int>> = listOf(
        "Mathematics" to 100,
        "English" to 40,
        "Kiswahili" to 40,
        "Integrated Science" to 50,
        "Social Studies" to 50,
        "CRE" to 30,
        "Agriculture" to 50,
        "Creative Arts" to 50,
        "Physical & Health Education" to 100,
        "Pre-Technical Studies" to 100,
        "Business Studies" to 100,
        "Home Science" to 100,
        "Computer Science" to 100,
    )

    private val FIRST = listOf(
        "Amina", "Brian", "Cynthia", "David", "Esther", "Faith", "George", "Hawa", "Ian",
        "Joy", "Kelvin", "Lucy", "Moses", "Nancy", "Oscar", "Purity", "Quinter", "Ronald",
        "Sharon", "Timothy", "Uzima", "Victor", "Wanjiku", "Xavier", "Yusuf", "Zawadi",
    )

    private val LAST = listOf(
        "Mwangi", "Ochieng", "Wanjiku", "Otieno", "Kariuki", "Achieng", "Njeri", "Kamau",
        "Mutua", "Omollo", "Waweru", "Chebet", "Kiptoo", "Onyango", "Muthoni", "Gitau",
    )

    fun branding(): ReportBranding = ReportBranding(
        schoolName = "Milimani Primary School",
        motto = "Nurture - Discover - Excel",
        address = "P.O. Box 12345, Nairobi",
        phone = "+254 700 000 000",
        email = "office@milimani.test",
        watermarkText = "Milimani Primary School",
    )

    fun level(percentage: Double, bands: List<ReportGradingBand>): String =
        bands.firstOrNull { percentage >= it.minPercentage }?.label ?: bands.last().label

    fun learnerName(sequence: Int): String = when (sequence) {
        7 -> "Ngũgĩ wa Thiong'o"
        9 -> "Wanjiku Wambui Njoroge Kipchoge Muthoni"
        11 -> "Amina Mwangi"
        else -> FIRST[(sequence * 13) % FIRST.size] + " " + LAST[(sequence * 29) % LAST.size]
    }

    fun exam(subjectCount: Int): TraditionalExamDto {
        val subjects = SUBJECTS.take(subjectCount).mapIndexed { index, (name, maxScore) ->
            SubjectConfigDto(subjectId = "subject-" + (index + 1), name = name, maxScore = maxScore)
        }
        return TraditionalExamDto(
            examId = "exam-grade-5-term-2",
            title = "End Term 2 2026 - Grade 5",
            term = ExamTerm.TERM_2,
            gradeLevel = "Grade 5",
            year = 2026,
            status = TraditionalExamStatus.PUBLISHED,
            subjects = subjects,
            createdAt = 1_760_000_000_000L,
        )
    }

    /** 500 learners with the approved lab's deterministic ability curve, ties and incompletes. */
    fun rows(subjectCount: Int): List<StudentGradeRowDto> {
        val exam = exam(subjectCount)
        val bands = TraditionalReportModel.DEFAULT_BANDS
        val out = ArrayList<StudentGradeRowDto>(LEARNERS)
        var sequence = 0
        CLASS_TAGS.forEachIndexed { classIndex, classTag ->
            repeat(CLASS_SIZE) { learnerIndex ->
                sequence++
                val incompleteKind = if (learnerIndex < INCOMPLETE_PER_CLASS) {
                    (classIndex + learnerIndex) % 3
                } else {
                    -1
                }
                val missingSubject = if (incompleteKind == 0) {
                    (classIndex + learnerIndex) % subjectCount
                } else {
                    -1
                }
                val zeroSubject = if (incompleteKind == 1 || incompleteKind == 2) {
                    (classIndex + learnerIndex + 1) % subjectCount
                } else {
                    -1
                }
                // Every 41st learner scores exactly 60 % per subject, giving deterministic ties.
                val ability = if (sequence % 41 == 0) 60 else 48 + (sequence * 37) % 40
                val results = ArrayList<TraditionalSubjectResultDto>(subjectCount)
                exam.subjects.forEachIndexed { subjectIndex, subject ->
                    if (subjectIndex == missingSubject) return@forEachIndexed
                    val max = subject.maxScore
                    val raw = if (subjectIndex == zeroSubject) {
                        0
                    } else {
                        (ability * max / 100.0).roundToInt().coerceIn(0, max)
                    }
                    val percentage = raw.toDouble() / max * 100.0
                    results += TraditionalSubjectResultDto(
                        subjectName = subject.name,
                        rawScore = raw,
                        maxScore = max,
                        percentage = percentage,
                        grade = level(percentage, bands),
                    )
                }
                val total = results.sumOf { it.percentage }
                out += StudentGradeRowDto(
                    rank = sequence,
                    studentId = "learner-" + sequence,
                    studentName = learnerName(sequence),
                    classTag = classTag,
                    subjects = results,
                    totalPercentage = total,
                    totalScore = 0,
                    overallGrade = level(total / subjectCount, bands),
                )
            }
        }
        return out
    }

    fun scaleSpec(
        subjectCount: Int,
        bands: List<ReportGradingBand> = emptyList(),
        blankOverallGrades: Boolean = false,
        classTeachers: Map<String, String> = CLASS_TEACHERS,
    ): GradeTableSpec {
        val baseRows = rows(subjectCount)
        return GradeTableSpec(
            title = "End Term 2 2026 - Grade 5",
            branding = branding(),
            exam = exam(subjectCount),
            rows = if (blankOverallGrades) baseRows.map { it.copy(overallGrade = "") } else baseRows,
            bands = bands,
            classTeachers = classTeachers,
        )
    }

    fun completeRows(subjectCount: Int): List<StudentGradeRowDto> = rows(subjectCount).filter { row ->
        row.subjects.size == subjectCount && row.subjects.all { it.rawScore > 0 }
    }

    // ------------------------------------------------------------- analysis

    private val ANALYSIS_SUBJECTS: List<String> = SUBJECTS.map { it.first }

    /** Deterministic class score for one subject: class index 4 is the best (position 1). */
    fun analysisClassMean(subject: String, classIndex: Int): Double =
        40.0 + 2.0 * ANALYSIS_SUBJECTS.indexOf(subject) + 5.0 * classIndex

    private fun teacherNameFor(classIndex: Int): String = when (classIndex) {
        0 -> "J. Mwangi"
        1 -> "A. Achieng"
        2 -> "P. Kariuki"
        3 -> "M. Njeri"
        else -> "Former teacher"
    }

    /**
     * 13-subject analysis fixture: five classes of 20 ranked learners, unique positions, five
     * teachers per subject and the L6 edge cases. Kiswahili has one co-taught (shared) bar and one
     * former-teacher bar; Agriculture routes class 5's marks to the muted Unassigned bar. Every
     * other subject has five attributed bars. In all cases the bars account for exactly the 100
     * ranked learners behind the page-1 means.
     */
    fun analysis13(): TraditionalGradeAnalysisDto {
        val classes = CLASS_TAGS.mapIndexed { classIndex, tag ->
            val means = ANALYSIS_SUBJECTS.associateWith { subject -> analysisClassMean(subject, classIndex) }
            ClassGradeAnalysisDto(
                classTag = tag,
                teacherName = CLASS_TEACHERS.getValue(tag),
                subjectAverages = means,
                subjectPositions = ANALYSIS_SUBJECTS.associateWith { 5 - classIndex },
                totalAverage = means.values.sum(),
                overallPosition = 5 - classIndex,
                studentCount = 20,
            )
        }
        val contributions = ANALYSIS_SUBJECTS.mapIndexed { subjectIndex, subject ->
            val teachers = CLASS_TAGS.mapIndexed { classIndex, tag ->
                TeacherContributionDto(
                    teacherUserId = "u-$subjectIndex-$classIndex",
                    teacherName = teacherNameFor(classIndex),
                    classTags = listOf(tag),
                    learners = 20,
                    mean = analysisClassMean(subject, classIndex),
                    shared = subject == "Kiswahili" && classIndex == 0,
                    former = subject == "Kiswahili" && classIndex == 4,
                )
            }
            val unassigned = if (subject == "Agriculture") {
                UnassignedContributionDto(learners = 20, mean = analysisClassMean(subject, 4))
            } else {
                null
            }
            SubjectTeacherContributionDto(
                subjectId = "s$subjectIndex",
                subjectName = subject,
                gradeMean = CLASS_TAGS.indices.map { analysisClassMean(subject, it) }.average(),
                teachers = if (unassigned == null) teachers else teachers.take(4),
                unassigned = unassigned,
            )
        }
        return TraditionalGradeAnalysisDto(
            examId = "exam-grade-5-term-2",
            gradeLevel = "Grade 5",
            term = "Term 2",
            year = 2026,
            classes = classes,
            subjects = ANALYSIS_SUBJECTS,
            subjectTeacherContributions = contributions,
            grading = GradingConfigDto(
                bands = listOf(
                    GradingBandDto("EE", 75),
                    GradingBandDto("ME", 60),
                    GradingBandDto("AE", 45),
                    GradingBandDto("BE", 30),
                    GradingBandDto("DE", 15),
                    GradingBandDto("E", 0),
                ),
            ),
        )
    }

    fun analysisSpec(): GradeAnalysisSpec = GradeAnalysisSpec(
        title = "Grade 5 Grade Analysis",
        branding = branding(),
        analysis = analysis13(),
    )
}
