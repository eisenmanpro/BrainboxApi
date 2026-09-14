package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * A console-configurable moderation policy override (Phase 7.4a), keyed by a
 * stable policy key. The code holds the defaults; a row here overrides one key
 * with its JSON [valueJson] (a scalar, or an object with a "value" field).
 */
@Entity
@Table(name = "moderation_policies")
class ModerationPolicyEntity : BaseEntity() {

    @Column(name = "policy_key", nullable = false, length = 64)
    var policyKey: String = ""

    @Column(name = "value_json", nullable = false, columnDefinition = "text")
    var valueJson: String = ""
}
