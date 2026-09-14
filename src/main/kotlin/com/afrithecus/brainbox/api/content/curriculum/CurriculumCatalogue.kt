package com.afrithecus.brainbox.api.content.curriculum

/**
 * The Tier 0 curriculum catalogue as authored resource data (Phase 7.5b-1).
 *
 * It is deliberately data, not code: adding a subject or a grade band is a new
 * JSON file, never a schema or service change. The labels are facts aligned to
 * the public Kenya CBC taxonomy; the catalogue itself is our own authored
 * mapping and no KICD or KNEC document is ingested, quoted or attributed.
 */
data class CurriculumCatalogue(
    val countryCode: String,
    val curriculum: String,
    val version: String,
    val name: String,
    val notes: String? = null,
    val subject: String,
    val grades: List<CatalogueGrade> = emptyList(),
)

data class CatalogueGrade(
    val gradeLevel: String,
    val strands: List<CatalogueStrand> = emptyList(),
)

data class CatalogueStrand(
    val code: String,
    val name: String,
    val descriptor: String? = null,
    val subStrands: List<CatalogueSubStrand> = emptyList(),
)

data class CatalogueSubStrand(
    val code: String,
    val name: String,
    val descriptor: String? = null,
    val learningOutcome: String,
    val topics: List<CatalogueTopic> = emptyList(),
)

data class CatalogueTopic(
    val code: String,
    val name: String,
    val descriptor: String? = null,
)
