package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/** A school (doc 01 §9). */
@Entity
@Table(name = "schools")
class SchoolEntity : BaseEntity() {

    @Column(nullable = false)
    var name: String = ""

    @Column(length = 128)
    var county: String? = null

    @Column(length = 255)
    var location: String? = null

    @Column(name = "logo_url", length = 512)
    var logoUrl: String? = null

    /** JSON array string of logo/cover image URLs for the public list card. */
    @Column(name = "logo_urls", columnDefinition = "text")
    var logoUrls: String? = null

    @Column(nullable = false)
    var rating: Double = 0.0

    @Column(name = "reviews_count", nullable = false)
    var reviewsCount: Int = 0

    @Column(name = "placement_rate", nullable = false)
    var placementRate: Int = 0

    @Column(name = "school_rank", nullable = false)
    var schoolRank: Int = 0

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
}
