package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/** A school (doc 01 §9). Extended by school-management migrations in later phases. */
@Entity
@Table(name = "schools")
class SchoolEntity : BaseEntity() {

    @Column(nullable = false)
    var name: String = ""
}
