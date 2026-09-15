package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.cbcratings.repository.CbcStrandRepository
import com.afrithecus.brainbox.api.content.batch.ContentBatchService
import com.afrithecus.brainbox.api.content.curriculum.CurriculumSeedBootstrap
import com.afrithecus.brainbox.api.content.curriculum.CurriculumSeeder
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumVersionRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * Phase 7.5b-1/7.5b-2a: the Tier 0 curriculum seeder discovers every catalogue on
 * the classpath, seeds the five Kenya CBC G4-G6 subjects deterministically and
 * idempotently, keeps the curriculum-level version row stable, and the startup
 * bootstrap stays off unless app.content.curriculum.seed-on-startup is enabled.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CurriculumSeederTests(
    @Autowired private val seeder: CurriculumSeeder,
    @Autowired private val bootstrap: CurriculumSeedBootstrap,
    @Autowired private val properties: AppContentProperties,
    @Autowired private val batch: ContentBatchService,
    @Autowired private val curriculumVersions: CurriculumVersionRepository,
    @Autowired private val strands: CbcStrandRepository,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val maps: CurriculumMapRepository,
) {

    @Test
    fun `seeding twice is idempotent`() {
        val first = seeder.seed()
        check(first.inserted > 0) { "the first seed run should insert rows" }
        val afterFirst = counts()

        val second = seeder.seed()
        val afterSecond = counts()

        check(afterFirst == afterSecond) {
            "row counts changed on the second seed run: " + afterFirst + " != " + afterSecond
        }
        check(second.inserted == 0) { "the second seed run inserted " + second.inserted + " rows" }
        check(second.updated > 0) { "the second seed run should have refreshed existing rows" }
    }

    @Test
    fun `every catalogue on the classpath is seeded`() {
        val summary = seeder.seed()

        check(summary.catalogues == CATALOGUES) { "expected " + CATALOGUES + " catalogues, got " + summary.catalogues }
        check(summary.subjects.toSet() == SUBJECTS) { "unexpected subjects: " + summary.subjects }
        check(summary.perSubject.size == CATALOGUES)
        check(summary.authoredStrands == TOTAL_STRANDS)
        check(summary.authoredSubStrands == TOTAL_SUBSTRANDS)
        check(summary.authoredTopics == TOTAL_TOPICS)
    }

    @Test
    fun `version row is created once and is not clobbered by a later subject file`() {
        val summary = seeder.seed()

        val version = curriculumVersions
            .findByCountryCodeAndCurriculumAndCurriculumVersion("KE", "CBC", VERSION)
        check(version != null) { "the curriculum version row was not created" }
        check(curriculumVersions.count() == 1L) { "curriculum_versions must hold one curriculum-level row" }
        check(summary.versionsInserted == 1) {
            "exactly one version row should be inserted for the version, got " + summary.versionsInserted
        }
        check(summary.versionsUpdated == summary.catalogues - 1) {
            "later subject files should only refresh the existing version, got " + summary.versionsUpdated
        }
        check(version.name == CURRICULUM_NAME) { "the version name was clobbered: " + version.name }
        check(version.isActive)
    }

    @Test
    fun `math grade four slice has the expected catalogue counts`() {
        seeder.seed()

        val g4Strands = strands.findAll().filter { it.code.startsWith("MAT4-") }
        check(g4Strands.count { it.level == "STRAND" } == 4)
        check(g4Strands.count { it.level == "SUBSTRAND" } == GRADE4_SUBSTRANDS)

        val g4Concepts = concepts.findAll().filter { it.code.startsWith("MAT4-") }
        check(g4Concepts.size == 4 + GRADE4_SUBSTRANDS + GRADE4_TOPICS)
        check(g4Concepts.count { TOPIC_CODE.containsMatchIn(it.code) } == GRADE4_TOPICS)

        val g4Maps = maps.findAll().filter {
            it.gradeLevel == "Grade 4" && it.curriculumVersion == VERSION &&
                it.strandCode?.startsWith("MAT4-") == true
        }
        check(g4Maps.size == 4 + GRADE4_SUBSTRANDS + GRADE4_TOPICS)
    }

    @Test
    fun `each new subject at Grades four to six has the expected depth`() {
        seeder.seed()

        NEW_SUBJECT_PREFIXES.forEach { (subject, prefix) ->
            (4..6).forEach { grade ->
                val codePrefix = prefix + grade + "-"
                val gradeStrands = strands.findAll().filter { it.code.startsWith(codePrefix) }
                check(gradeStrands.count { it.level == "STRAND" } == GRADE_STRANDS) {
                    subject + " Grade " + grade + " strand count"
                }
                check(gradeStrands.count { it.level == "SUBSTRAND" } == GRADE_SUBSTRANDS) {
                    subject + " Grade " + grade + " sub-strand count"
                }
                val gradeConcepts = concepts.findAll().filter { it.code.startsWith(codePrefix) }
                check(gradeConcepts.size == GRADE_STRANDS + GRADE_SUBSTRANDS + GRADE_TOPICS) {
                    subject + " Grade " + grade + " concept count"
                }
                check(gradeConcepts.count { TOPIC_CODE.containsMatchIn(it.code) } == GRADE_TOPICS) {
                    subject + " Grade " + grade + " topic count"
                }
            }
        }
    }

    @Test
    fun `whole catalogue has the expected depth`() {
        seeder.seed()

        check(curriculumVersions.findByCountryCodeAndCurriculumAndCurriculumVersion("KE", "CBC", VERSION) != null)
        check(curriculumVersions.count() == 1L)
        check(strands.findAll().count { it.curriculumVersion == VERSION } == TOTAL_STRANDS + TOTAL_SUBSTRANDS)
        check(concepts.findAll().count { CATALOGUE_CONCEPT.containsMatchIn(it.code) } == TOTAL_CONCEPTS)
        check(maps.findAll().count { it.curriculumVersion == VERSION } == TOTAL_CONCEPTS)
    }

    @Test
    fun `every topic concept maps to a strand and sub-strand with a version`() {
        seeder.seed()

        val topicConcepts = concepts.findAll().filter { TOPIC_CODE.containsMatchIn(it.code) }
        check(topicConcepts.size == TOTAL_TOPICS)

        topicConcepts.forEach { topic ->
            val map = maps.findFirstByConceptIdAndCountryCodeAndCurriculumOrderBySortOrderAsc(topic.id, "KE", "CBC")
            check(map != null) { "topic " + topic.code + " has no curriculum map" }
            check(!map.strandCode.isNullOrBlank()) { "topic " + topic.code + " has no strand code" }
            check(!map.substrandCode.isNullOrBlank()) { "topic " + topic.code + " has no sub-strand code" }
            check(map.curriculumVersion == VERSION) { "topic " + topic.code + " has no curriculum version" }
        }
    }

    @Test
    fun `strand sub-strand hierarchy has no orphan topics`() {
        seeder.seed()

        val catalogueStrands = strands.findAll().filter { it.curriculumVersion == VERSION }
        val tops = catalogueStrands.filter { it.level == "STRAND" }
        val subs = catalogueStrands.filter { it.level == "SUBSTRAND" }
        check(tops.size == TOTAL_STRANDS)
        check(subs.size == TOTAL_SUBSTRANDS)

        subs.forEach { sub ->
            val parentId = sub.parentId ?: error("sub-strand " + sub.code + " has no parent")
            check(strands.findById(parentId).orElseThrow().level == "STRAND")
        }

        val topicConcepts = concepts.findAll().filter { TOPIC_CODE.containsMatchIn(it.code) }
        topicConcepts.forEach { topic ->
            val parentId = topic.parentId ?: error("topic " + topic.code + " has no parent")
            val subConcept = concepts.findById(parentId).orElseThrow()
            check(subConcept.code == topic.code.substringBeforeLast("-T")) {
                "topic " + topic.code + " points at " + subConcept.code
            }
            val strandConcept = concepts.findById(
                subConcept.parentId ?: error("sub-strand concept " + subConcept.code + " has no parent")
            ).orElseThrow()
            check(strandConcept.parentId == null)
        }
    }

    @Test
    fun `batch producer resolves the new subjects at Grade four`() {
        seeder.seed()

        NEW_SUBJECTS.forEach { subject ->
            val topics = batch.resolveTopicConcepts("Grade 4", subject)
            check(topics.isNotEmpty()) { "no leaf topics resolved for " + subject + " Grade 4" }
            check(topics.all { it.subject == subject }) { "resolved topics leaked another subject for " + subject }
        }
    }

    @Test
    fun `bootstrap does not seed when seed-on-startup is disabled`() {
        check(!properties.curriculum.seedOnStartup) { "the test profile must not enable curriculum seeding" }
        val before = counts()

        bootstrap.onApplicationReady()

        check(counts() == before) { "the disabled bootstrap changed the database" }
        check(curriculumVersions.findByCountryCodeAndCurriculumAndCurriculumVersion("KE", "CBC", VERSION) == null) {
            "the disabled bootstrap seeded a curriculum version"
        }
    }

    private fun counts(): List<Long> = listOf(
        curriculumVersions.count(),
        strands.count(),
        concepts.count(),
        maps.count(),
    )

    private companion object {
        const val VERSION = "v1"
        const val CURRICULUM_NAME = "Kenya CBC Tier 0 curriculum skeleton"
        const val CATALOGUES = 5

        // Mathematics Grade 4 depth, unchanged from 7.5b-1.
        const val GRADE4_SUBSTRANDS = 18
        const val GRADE4_TOPICS = 67

        // The four new subjects: 4 strands, 16 sub-strands and 64 topics per grade.
        const val GRADE_STRANDS = 4
        const val GRADE_SUBSTRANDS = 16
        const val GRADE_TOPICS = 64

        // Whole-catalogue totals: Mathematics (12/55/209) + 4 x (12/48/192).
        const val TOTAL_STRANDS = 12 + 4 * 12
        const val TOTAL_SUBSTRANDS = 55 + 4 * 48
        const val TOTAL_TOPICS = 209 + 4 * 192
        const val TOTAL_CONCEPTS = TOTAL_STRANDS + TOTAL_SUBSTRANDS + TOTAL_TOPICS

        val SUBJECTS = setOf("Mathematics", "English", "Integrated Science", "Kiswahili", "Social Studies")
        val NEW_SUBJECTS = listOf("English", "Integrated Science", "Kiswahili", "Social Studies")
        val NEW_SUBJECT_PREFIXES = listOf(
            "English" to "ENG",
            "Integrated Science" to "SCI",
            "Kiswahili" to "KIS",
            "Social Studies" to "SST",
        )
        val TOPIC_CODE = Regex("-S\\d+-T\\d+$")
        val CATALOGUE_CONCEPT = Regex("^[A-Z]{3}[4-6]-")
    }
}
