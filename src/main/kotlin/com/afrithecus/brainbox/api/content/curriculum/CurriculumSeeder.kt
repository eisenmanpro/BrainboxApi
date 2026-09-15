package com.afrithecus.brainbox.api.content.curriculum

import com.afrithecus.brainbox.api.cbcratings.entity.CbcStrandEntity
import com.afrithecus.brainbox.api.cbcratings.repository.CbcStrandRepository
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumVersionEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumVersionRepository
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Phase 7.5b-1/7.5b-2a: seeds the Tier 0 curriculum skeleton from authored
 * resource data (never from the LLM). Discovery is a classpath wildcard, so
 * every JSON catalogue under `curriculum/` is seeded and adding a subject or a
 * grade band is a data-only change.
 *
 * It is deterministic and idempotent: every row id is derived from a stable
 * catalogue code with [UUID.nameUUIDFromBytes], and each upsert looks the row up
 * by its stable code before writing, so a second run inserts nothing and changes
 * no row counts. The `curriculum_versions` row is curriculum-level (keyed by
 * country + curriculum + version): the first catalogue to seed it inserts it and
 * later subject files only refresh `is_active`, so no later subject can clobber
 * the version name or notes.
 *
 * The catalogues are our own authored mapping aligned to the public Kenya CBC
 * strand/sub-strand labels; KICD documents are not a source.
 */
@Service
class CurriculumSeeder(
    private val objectMapper: ObjectMapper,
    private val curriculumVersions: CurriculumVersionRepository,
    private val cbcStrands: CbcStrandRepository,
    private val concepts: ConceptRepository,
    private val curriculumMaps: CurriculumMapRepository,
) {

    @Transactional
    fun seed(): CurriculumSeedSummary {
        val catalogues = loadCatalogues()
        val totals = Counters()
        val perSubject = mutableListOf<SubjectSeedSummary>()

        catalogues.forEach { catalogue ->
            val counters = Counters()
            seedCatalogue(catalogue, counters)
            totals.add(counters)
            perSubject += counters.toSubjectSummary(catalogue)
        }

        return totals.toSummary(catalogues, perSubject)
    }

    private fun seedCatalogue(catalogue: CurriculumCatalogue, counters: Counters) {
        upsertVersion(catalogue, counters)

        var sortOrder = 0
        catalogue.grades.forEach { grade ->
            grade.strands.forEach { strand ->
                val strandId = derivedId("cbc-strand", strand.code)
                val strandParentId: UUID? = null
                val strandDescriptor = strand.descriptor ?: strand.name
                upsertStrand(
                    id = strandId,
                    code = strand.code,
                    name = strand.name,
                    descriptor = strandDescriptor,
                    gradeLevel = grade.gradeLevel,
                    subject = catalogue.subject,
                    sortOrder = ++sortOrder,
                    parentId = strandParentId,
                    level = "STRAND",
                    curriculumVersion = catalogue.version,
                    counters = counters,
                )
                val strandConceptId = derivedId("concept", strand.code)
                upsertConcept(
                    id = strandConceptId,
                    code = strand.code,
                    name = strand.name,
                    description = strandDescriptor,
                    subject = catalogue.subject,
                    parentId = null,
                    sortOrder = ++sortOrder,
                    counters = counters,
                )
                upsertMapping(
                    id = derivedId("curriculum-map", conceptKey(strand.code, grade.gradeLevel, catalogue)),
                    conceptId = strandConceptId,
                    catalogue = catalogue,
                    gradeLevel = grade.gradeLevel,
                    strandCode = strand.code,
                    strandName = strand.name,
                    substrandCode = null,
                    substrandName = null,
                    learningOutcome = strandDescriptor,
                    sortOrder = ++sortOrder,
                    counters = counters,
                )

                strand.subStrands.forEach { sub ->
                    val subId = derivedId("cbc-strand", sub.code)
                    val subDescriptor = sub.descriptor ?: sub.learningOutcome
                    upsertStrand(
                        id = subId,
                        code = sub.code,
                        name = sub.name,
                        descriptor = subDescriptor,
                        gradeLevel = grade.gradeLevel,
                        subject = catalogue.subject,
                        sortOrder = ++sortOrder,
                        parentId = strandId,
                        level = "SUBSTRAND",
                        curriculumVersion = catalogue.version,
                        counters = counters,
                    )
                    val subConceptId = derivedId("concept", sub.code)
                    upsertConcept(
                        id = subConceptId,
                        code = sub.code,
                        name = sub.name,
                        description = subDescriptor,
                        subject = catalogue.subject,
                        parentId = strandConceptId,
                        sortOrder = ++sortOrder,
                        counters = counters,
                    )
                    upsertMapping(
                        id = derivedId("curriculum-map", conceptKey(sub.code, grade.gradeLevel, catalogue)),
                        conceptId = subConceptId,
                        catalogue = catalogue,
                        gradeLevel = grade.gradeLevel,
                        strandCode = strand.code,
                        strandName = strand.name,
                        substrandCode = sub.code,
                        substrandName = sub.name,
                        learningOutcome = sub.learningOutcome,
                        sortOrder = ++sortOrder,
                        counters = counters,
                    )

                    sub.topics.forEach { topic ->
                        val topicConceptId = derivedId("concept", topic.code)
                        upsertConcept(
                            id = topicConceptId,
                            code = topic.code,
                            name = topic.name,
                            description = topic.descriptor ?: sub.learningOutcome,
                            subject = catalogue.subject,
                            parentId = subConceptId,
                            sortOrder = ++sortOrder,
                            counters = counters,
                        )
                        upsertMapping(
                            id = derivedId("curriculum-map", conceptKey(topic.code, grade.gradeLevel, catalogue)),
                            conceptId = topicConceptId,
                            catalogue = catalogue,
                            gradeLevel = grade.gradeLevel,
                            strandCode = strand.code,
                            strandName = strand.name,
                            substrandCode = sub.code,
                            substrandName = sub.name,
                            learningOutcome = sub.learningOutcome,
                            sortOrder = ++sortOrder,
                            counters = counters,
                        )
                    }
                }
            }
        }
    }

    /**
     * Every catalogue on the classpath under `curriculum/`. Sorted by filename so
     * the seed order, the version metadata and the aggregated summary are
     * deterministic across runs and environments.
     */
    private fun loadCatalogues(): List<CurriculumCatalogue> {
        val resources = PathMatchingResourcePatternResolver()
            .getResources(RESOURCE_PATTERN)
            .sortedBy { it.filename ?: it.description }
        check(resources.isNotEmpty()) { "curriculum catalogues not found on the classpath: " + RESOURCE_PATTERN }
        return resources.map { resource ->
            resource.inputStream.use { objectMapper.readValue(it, CurriculumCatalogue::class.java) }
        }
    }

    /**
     * The version row is curriculum-level, not subject-level: insert it on the
     * first catalogue, then only refresh `is_active`. A later subject file must
     * never overwrite the name or notes authored by the file that created it.
     */
    private fun upsertVersion(catalogue: CurriculumCatalogue, counters: Counters) {
        val existing = curriculumVersions
            .findByCountryCodeAndCurriculumAndCurriculumVersion(catalogue.countryCode, catalogue.curriculum, catalogue.version)
        if (existing == null) {
            curriculumVersions.save(
                CurriculumVersionEntity().apply {
                    id = derivedId("curriculum-version", catalogue.countryCode + ":" + catalogue.curriculum + ":" + catalogue.version)
                    countryCode = catalogue.countryCode
                    curriculum = catalogue.curriculum
                    curriculumVersion = catalogue.version
                    name = catalogue.name
                    notes = catalogue.notes
                    isActive = true
                }
            )
            counters.versionsInserted++
        } else {
            existing.isActive = true
            counters.versionsUpdated++
        }
    }

    private fun upsertStrand(
        id: UUID,
        code: String,
        name: String,
        descriptor: String,
        gradeLevel: String,
        subject: String,
        sortOrder: Int,
        parentId: UUID?,
        level: String,
        curriculumVersion: String,
        counters: Counters,
    ) {
        val existing = cbcStrands.findByCode(code)
        if (existing == null) {
            cbcStrands.save(
                CbcStrandEntity().apply {
                    this.id = id
                    this.code = code
                    this.name = name
                    this.descriptor = descriptor
                    this.gradeLevel = gradeLevel
                    this.subject = subject
                    this.sortOrder = sortOrder
                    this.parentId = parentId
                    this.level = level
                    this.curriculumVersion = curriculumVersion
                }
            )
            counters.strandsInserted++
        } else {
            existing.name = name
            existing.descriptor = descriptor
            existing.gradeLevel = gradeLevel
            existing.subject = subject
            existing.sortOrder = sortOrder
            existing.parentId = parentId
            existing.level = level
            existing.curriculumVersion = curriculumVersion
            counters.strandsUpdated++
        }
    }

    private fun upsertConcept(
        id: UUID,
        code: String,
        name: String,
        description: String?,
        subject: String,
        parentId: UUID?,
        sortOrder: Int,
        counters: Counters,
    ) {
        val existing = concepts.findByCode(code)
        if (existing == null) {
            concepts.save(
                ConceptEntity().apply {
                    this.id = id
                    this.code = code
                    this.name = name
                    this.description = description
                    this.subject = subject
                    this.parentId = parentId
                    this.sortOrder = sortOrder
                }
            )
            counters.conceptsInserted++
        } else {
            existing.name = name
            existing.description = description
            existing.subject = subject
            existing.parentId = parentId
            existing.sortOrder = sortOrder
            counters.conceptsUpdated++
        }
    }

    private fun upsertMapping(
        id: UUID,
        conceptId: UUID,
        catalogue: CurriculumCatalogue,
        gradeLevel: String,
        strandCode: String,
        strandName: String,
        substrandCode: String?,
        substrandName: String?,
        learningOutcome: String,
        sortOrder: Int,
        counters: Counters,
    ) {
        val existing = curriculumMaps.findById(id).orElse(null)
        if (existing == null) {
            curriculumMaps.save(
                CurriculumMapEntity().apply {
                    this.id = id
                    this.conceptId = conceptId
                    this.countryCode = catalogue.countryCode
                    this.curriculum = catalogue.curriculum
                    this.gradeLevel = gradeLevel
                    this.strandCode = strandCode
                    this.strandName = strandName
                    this.substrandCode = substrandCode
                    this.substrandName = substrandName
                    this.learningOutcome = learningOutcome
                    this.curriculumVersion = catalogue.version
                    this.sortOrder = sortOrder
                }
            )
            counters.mappingsInserted++
        } else {
            existing.conceptId = conceptId
            existing.countryCode = catalogue.countryCode
            existing.curriculum = catalogue.curriculum
            existing.gradeLevel = gradeLevel
            existing.strandCode = strandCode
            existing.strandName = strandName
            existing.substrandCode = substrandCode
            existing.substrandName = substrandName
            existing.learningOutcome = learningOutcome
            existing.curriculumVersion = catalogue.version
            existing.sortOrder = sortOrder
            counters.mappingsUpdated++
        }
    }

    private fun derivedId(kind: String, key: String): UUID =
        UUID.nameUUIDFromBytes(("brainbox:curriculum:" + kind + ":" + key).toByteArray(StandardCharsets.UTF_8))

    private fun conceptKey(conceptCode: String, gradeLevel: String, catalogue: CurriculumCatalogue): String =
        catalogue.countryCode + ":" + catalogue.curriculum + ":" + gradeLevel + ":" + conceptCode

    private class Counters {
        var versionsInserted = 0
        var versionsUpdated = 0
        var strandsInserted = 0
        var strandsUpdated = 0
        var conceptsInserted = 0
        var conceptsUpdated = 0
        var mappingsInserted = 0
        var mappingsUpdated = 0

        fun add(other: Counters) {
            versionsInserted += other.versionsInserted
            versionsUpdated += other.versionsUpdated
            strandsInserted += other.strandsInserted
            strandsUpdated += other.strandsUpdated
            conceptsInserted += other.conceptsInserted
            conceptsUpdated += other.conceptsUpdated
            mappingsInserted += other.mappingsInserted
            mappingsUpdated += other.mappingsUpdated
        }

        fun toSubjectSummary(catalogue: CurriculumCatalogue): SubjectSeedSummary = SubjectSeedSummary(
            subject = catalogue.subject,
            grades = catalogue.grades.size,
            strands = catalogue.grades.sumOf { it.strands.size },
            subStrands = catalogue.grades.sumOf { grade -> grade.strands.sumOf { it.subStrands.size } },
            topics = catalogue.grades.sumOf { grade ->
                grade.strands.sumOf { strand -> strand.subStrands.sumOf { it.topics.size } }
            },
            versionsInserted = versionsInserted,
            versionsUpdated = versionsUpdated,
            strandsInserted = strandsInserted,
            strandsUpdated = strandsUpdated,
            conceptsInserted = conceptsInserted,
            conceptsUpdated = conceptsUpdated,
            mappingsInserted = mappingsInserted,
            mappingsUpdated = mappingsUpdated,
        )

        fun toSummary(
            catalogues: List<CurriculumCatalogue>,
            perSubject: List<SubjectSeedSummary>,
        ): CurriculumSeedSummary = CurriculumSeedSummary(
            version = catalogues.map { it.version }.distinct().joinToString(","),
            grades = catalogues.sumOf { it.grades.size },
            catalogues = catalogues.size,
            subjects = catalogues.map { it.subject },
            versionsInserted = versionsInserted,
            versionsUpdated = versionsUpdated,
            strandsInserted = strandsInserted,
            strandsUpdated = strandsUpdated,
            conceptsInserted = conceptsInserted,
            conceptsUpdated = conceptsUpdated,
            mappingsInserted = mappingsInserted,
            mappingsUpdated = mappingsUpdated,
            authoredStrands = perSubject.sumOf { it.strands },
            authoredSubStrands = perSubject.sumOf { it.subStrands },
            authoredTopics = perSubject.sumOf { it.topics },
            perSubject = perSubject,
        )
    }

    companion object {
        /** Classpath wildcard pattern: every JSON catalogue under the curriculum directory is one subject. */
        const val RESOURCE_PATTERN: String = "classpath*:curriculum/*.json"
    }
}

/** Counts inserted/updated per table for one catalogue or one whole seed run. */
data class CurriculumSeedSummary(
    val version: String,
    val grades: Int,
    val catalogues: Int,
    val subjects: List<String>,
    val versionsInserted: Int,
    val versionsUpdated: Int,
    val strandsInserted: Int,
    val strandsUpdated: Int,
    val conceptsInserted: Int,
    val conceptsUpdated: Int,
    val mappingsInserted: Int,
    val mappingsUpdated: Int,
    val authoredStrands: Int,
    val authoredSubStrands: Int,
    val authoredTopics: Int,
    val perSubject: List<SubjectSeedSummary> = emptyList(),
) {
    val inserted: Int get() = versionsInserted + strandsInserted + conceptsInserted + mappingsInserted
    val updated: Int get() = versionsUpdated + strandsUpdated + conceptsUpdated + mappingsUpdated
}

/** Authored depth and write counters for one subject catalogue. */
data class SubjectSeedSummary(
    val subject: String,
    val grades: Int,
    val strands: Int,
    val subStrands: Int,
    val topics: Int,
    val versionsInserted: Int,
    val versionsUpdated: Int,
    val strandsInserted: Int,
    val strandsUpdated: Int,
    val conceptsInserted: Int,
    val conceptsUpdated: Int,
    val mappingsInserted: Int,
    val mappingsUpdated: Int,
)
