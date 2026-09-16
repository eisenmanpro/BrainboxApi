package com.afrithecus.brainbox.api.ops

import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.ops.web.OpsCoverageOverall
import com.afrithecus.brainbox.api.ops.web.OpsCoveragePayload
import com.afrithecus.brainbox.api.ops.web.OpsCoverageRow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * O1 shelf coverage: how much of the curriculum shelf is actually published.
 * Reads the concept/curriculum_map catalogue (leaf topics) and the published
 * content_units. Coverage is a point-in-time snapshot, so the rollup stores its
 * scalars each hour; the per subject x grade rows are served live.
 *
 * A leaf topic is a concept with a parent and no children, mapped in
 * curriculum_map. "Covered" means at least one published (REVIEWED) notes or quiz
 * unit points at the concept. Subject x grade is the grouping key because one
 * concept can be mapped to several grades.
 */
@Service
class OpsCoverageService(
    private val units: ContentUnitRepository,
) {

    @Transactional(readOnly = true)
    fun coverage(): OpsCoveragePayload {
        val leaves = units.findCoverageLeafTopics()
        val covered = units.findCoveredConceptIds().toHashSet()
        val publishedCounts = units.countPublishedBySubjectGradeTask(PUBLISHED_TASK_TYPES)

        val byKey = HashMap<Pair<String, String>, MutableMap<String, Long>>()
        publishedCounts.forEach { row ->
            byKey.getOrPut(row.subject to row.gradeLevel) { mutableMapOf() }[row.taskType] = row.total
        }

        val rows = leaves
            .groupBy { it.subject to it.gradeLevel }
            .map { (key, group) ->
                val conceptIds = group.map { it.conceptId }.distinct()
                OpsCoverageRow(
                    subject = key.first,
                    grade = key.second,
                    totalTopics = conceptIds.size.toLong(),
                    coveredTopics = conceptIds.count { it in covered }.toLong(),
                    practicePapersPublished = byKey[key]?.get(TASK_PRACTICE_PAPER) ?: 0L,
                    studyGuidesPublished = byKey[key]?.get(TASK_STUDY_GUIDE) ?: 0L,
                )
            }
            .sortedWith(compareBy({ it.subject }, { it.grade }))

        val allConcepts = leaves.map { it.conceptId }.distinct()
        val overallTotal = allConcepts.size.toLong()
        val overallCovered = allConcepts.count { it in covered }.toLong()
        val overall = OpsCoverageOverall(
            totalTopics = overallTotal,
            coveredTopics = overallCovered,
            coverageRate = if (overallTotal == 0L) null else overallCovered.toDouble() / overallTotal.toDouble(),
        )
        return OpsCoveragePayload(overall = overall, rows = rows)
    }

    private companion object {
        const val TASK_PRACTICE_PAPER = "PRACTICE_PAPER"
        const val TASK_STUDY_GUIDE = "STUDY_GUIDE"
        val PUBLISHED_TASK_TYPES = listOf("NOTES", "QUIZ", TASK_PRACTICE_PAPER, TASK_STUDY_GUIDE)
    }
}
