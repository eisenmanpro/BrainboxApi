package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * A versioned generation prompt (Phase 7.2). The semantic version is stored in
 * `prompt_version` to avoid colliding with BaseEntity's optimistic-lock
 * `version` column; [evalScore] carries the measured accuracy so the console can
 * promote or roll back a prompt.
 */
@Entity
@Table(name = "prompt_versions")
class PromptVersionEntity : BaseEntity() {

    @Column(name = "prompt_key", nullable = false, length = 128)
    var promptKey: String = ""

    @Column(name = "prompt_version", nullable = false, length = 32)
    var promptVersion: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var body: String = ""

    @Column(name = "eval_score")
    var evalScore: Double? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = false
}
