package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One term's frozen school analytics, kept so the next term can show a real
 * trend instead of a null placeholder (api_admin_changes.md analytics).
 */
@Entity
@Table(name = "school_performance_snapshots")
class SchoolPerformanceSnapshotEntity : BaseEntity() {

    @Column(name = "school_id", nullable = false)
    var schoolId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 16)
    var term: String = ""

    @Column(name = "snapshot_year", nullable = false)
    var snapshotYear: Int = 0

    @Column(name = "overall_performance", nullable = false)
    var overallPerformance: Double = 0.0

    /** JSON map of classId -> average for per-class trends. */
    @Column(name = "class_averages", columnDefinition = "text")
    var classAverages: String? = null

    @Column(name = "generated_at", nullable = false)
    var generatedAt: Instant = Instant.now()
}
