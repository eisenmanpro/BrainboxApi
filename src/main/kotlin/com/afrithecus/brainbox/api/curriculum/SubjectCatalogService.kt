package com.afrithecus.brainbox.api.curriculum

import com.afrithecus.brainbox.api.curriculum.repository.SubjectRepository
import com.afrithecus.brainbox.api.curriculum.web.SubjectPayload
import com.afrithecus.brainbox.api.exams.QuestionCodec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Reads the BrainBox-managed subject catalogue for the client drop-downs (N3). */
@Service
class SubjectCatalogService(
    private val repository: SubjectRepository,
    private val codec: QuestionCodec,
) {

    @Transactional(readOnly = true)
    fun all(): List<SubjectPayload> = repository.findAllByIsActiveTrueOrderBySortOrderAscNameAsc().map { row ->
        SubjectPayload(
            id = row.id.toString(),
            code = row.code,
            name = row.name,
            gradeBands = codec.parseList(row.gradeBands).orEmpty(),
        )
    }

    /** The catalogue name for [raw], case-insensitively, or null when unrecognised. */
    @Transactional(readOnly = true)
    fun canonicalName(raw: String): String? =
        repository.findByNameIgnoreCase(raw.trim())?.takeIf { it.isActive }?.name
}
