package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ConceptRepository : JpaRepository<ConceptEntity, UUID> {

    fun findByCode(code: String): ConceptEntity?

    fun findAllBySubjectOrderBySortOrderAsc(subject: String): List<ConceptEntity>

    /**
     * Phase 7.5e Tier 1 producer: the leaf `topic` concepts of the Tier 0
     * skeleton for one national grade. A topic has a parent (strand/sub-strand)
     * and no children of its own, and it is mapped in curriculum_map for the
     * requested country/curriculum/grade. [subject] is optional; null means all
     * subjects. Results are ordered by the authored sort_order so a limited batch
     * is deterministic.
     */
    @Query(
        "SELECT c FROM ConceptEntity c " +
            "WHERE c.parentId IS NOT NULL " +
            "AND NOT EXISTS (SELECT child FROM ConceptEntity child WHERE child.parentId = c.id) " +
            "AND (:subject IS NULL OR c.subject = :subject) " +
            "AND EXISTS (" +
            "SELECT m FROM CurriculumMapEntity m " +
            "WHERE m.conceptId = c.id " +
            "AND m.countryCode = :countryCode " +
            "AND m.curriculum = :curriculum " +
            "AND m.gradeLevel = :gradeLevel" +
            ") " +
            "ORDER BY c.sortOrder ASC"
    )
    fun findLeafTopics(
        @Param("countryCode") countryCode: String,
        @Param("curriculum") curriculum: String,
        @Param("gradeLevel") gradeLevel: String,
        @Param("subject") subject: String?,
    ): List<ConceptEntity>
}
