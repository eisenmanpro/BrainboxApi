package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Whether a learner- or teacher-facing row was written by a model, and where its review
 * stands.
 *
 * A projected row keeps the id of the content unit it came from (a learning post, a
 * readable chunk and a practice-paper exam all use `unit.id`), so provenance is resolved
 * from that unit rather than duplicated onto every row. A row with no unit behind it was
 * authored by a person: it is not model-generated and needs no model review.
 */
data class ContentProvenance(
    val generated: Boolean,
    val reviewState: String,
) {
    companion object {
        /** A row a human authored: no generation, nothing for the review gate to decide. */
        val HUMAN_AUTHORED = ContentProvenance(generated = false, reviewState = REVIEWED)

        const val GENERATED = "GENERATED"
        const val REVIEWED = "REVIEWED"
    }
}

@Service
class ContentProvenanceService(private val contentUnits: ContentUnitRepository) {

    /** Provenance of one row, looked up by its (unit) id. */
    fun of(rowId: UUID): ContentProvenance =
        contentUnits.findById(rowId).orElse(null)?.provenance()
            ?: ContentProvenance.HUMAN_AUTHORED

    /**
     * Provenance for a page of rows in one query, keyed by row id. Rows with no unit are
     * absent; callers fall back to [ContentProvenance.HUMAN_AUTHORED].
     */
    fun ofAll(rowIds: Collection<UUID>): Map<UUID, ContentProvenance> {
        if (rowIds.isEmpty()) return emptyMap()
        return contentUnits.findAllById(rowIds).associate { it.id to it.provenance() }
    }

    private fun com.afrithecus.brainbox.api.content.entity.ContentUnitEntity.provenance() =
        ContentProvenance(
            generated = provenance == ContentProvenance.GENERATED,
            reviewState = reviewState,
        )
}
