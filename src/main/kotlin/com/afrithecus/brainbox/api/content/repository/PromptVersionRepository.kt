package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.PromptVersionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface PromptVersionRepository : JpaRepository<PromptVersionEntity, UUID> {

    /**
     * The semantic version is mapped to the `prompt_version` column (BaseEntity
     * owns the optimistic-lock `version` column), so this finder is declared
     * explicitly to keep the requested `...AndVersion` name.
     */
    @Query("SELECT p FROM PromptVersionEntity p WHERE p.promptKey = :promptKey AND p.promptVersion = :version")
    fun findByPromptKeyAndVersion(
        @Param("promptKey") promptKey: String,
        @Param("version") version: String,
    ): PromptVersionEntity?

    fun findByPromptKeyAndIsActiveTrue(promptKey: String): PromptVersionEntity?
}
