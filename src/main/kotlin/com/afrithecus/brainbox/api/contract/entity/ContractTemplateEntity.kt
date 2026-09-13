package com.afrithecus.brainbox.api.contract.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/** A canonical contract template (doc 04 section 11.4). */
@Entity
@Table(name = "contract_templates")
class ContractTemplateEntity : BaseEntity() {

    @Column(nullable = false, length = 200)
    var title: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var description: String = ""

    @Column(nullable = false, length = 64)
    var category: String = ""

    /** JSON array string of default commitments. */
    @Column(name = "default_commitments", columnDefinition = "text")
    var defaultCommitments: String? = null
}
