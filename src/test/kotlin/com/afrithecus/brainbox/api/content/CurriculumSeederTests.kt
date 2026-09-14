package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.cbcratings.repository.CbcStrandRepository
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
 * Phase 7.5b-1: the Tier 0 curriculum seeder is deterministic and idempotent,
 * the authored Mathematics G4-G6 catalogue lands at the expected depth, and the
 * startup bootstrap stays off unless app.content.curriculum.seed-on-startup is
 * explicitly enabled.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CurriculumSeederTests(
    @Autowired private val seeder: CurriculumSeeder,
    @Autowired private val bootstrap: CurriculumSeedBootstrap,
    @Autowired private val properties: AppContentProperties,
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
    fun `math grade four slice has the expected catalogue counts`() {
        seeder.seed()

        val g4Strands = strands.findAll().filter { it.code.startsWith("MAT4-") }
        check(g4Strands.count { it.level == "STRAND" } == 4)
        check(g4Strands.count { it.level == "SUBSTRAND" } == GRADE4_SUBSTRANDS)

        val g4Concepts = concepts.findAll().filter { it.code.startsWith("MAT4-") }
        check(g4Concepts.size == 4 + GRADE4_SUBSTRANDS + GRADE4_TOPICS)
        check(g4Concepts.count { TOPIC_CODE.containsMatchIn(it.code) } == GRADE4_TOPICS)

        val g4Maps = maps.findAll()
            .filter { it.gradeLevel == "Grade 4" && it.curriculumVersion == VERSION }
        check(g4Maps.size == 4 + GRADE4_SUBSTRANDS + GRADE4_TOPICS)
    }

    @Test
    fun `whole catalogue has the expected depth`() {
        seeder.seed()

        check(curriculumVersions.findByCountryCodeAndCurriculumAndCurriculumVersion("KE", "CBC", VERSION) != null)
        check(curriculumVersions.count() == 1L)
        check(strands.findAll().count { it.curriculumVersion == VERSION } == 12 + TOTAL_SUBSTRANDS)
        check(concepts.findAll().count { CATALOGUE_CONCEPT.containsMatchIn(it.code) } == 12 + TOTAL_SUBSTRANDS + TOTAL_TOPICS)
        check(maps.findAll().count { it.curriculumVersion == VERSION } == 12 + TOTAL_SUBSTRANDS + TOTAL_TOPICS)
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
        check(tops.size == 12)
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
            val strandConcept = concepts.findById(subConcept.parentId ?: error("sub-strand concept " + subConcept.code + " has no parent")).orElseThrow()
            check(strandConcept.parentId == null)
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
        const val GRADE4_SUBSTRANDS = 18
        const val GRADE4_TOPICS = 67
        const val TOTAL_SUBSTRANDS = 55
        const val TOTAL_TOPICS = 209
        val TOPIC_CODE = Regex("-S\\d+-T\\d+$")
        val CATALOGUE_CONCEPT = Regex("^MAT[4-6]-")
    }
}
