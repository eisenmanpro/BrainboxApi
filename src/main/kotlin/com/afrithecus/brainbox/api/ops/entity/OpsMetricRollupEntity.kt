package com.afrithecus.brainbox.api.ops.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/**
 * One aggregated operations metric for one hour bucket (O1). The backend owns
 * this history in its own database; the admin ops API and the future internal
 * frontend read it. Exactly one row per (bucketStart, metric, dimension), so
 * recomputing an hour replaces rather than duplicates.
 *
 * [metric] is a stable name from [com.afrithecus.brainbox.api.ops.OpsMetrics];
 * [dimension] is a free but bounded tag (a provider, a reason, or empty for a
 * global value). No per-topic cardinality: dimensions are coarse.
 */
@Entity
@Table(name = "ops_metric_rollup")
class OpsMetricRollupEntity : BaseEntity() {

    @Column(name = "bucket_start", nullable = false)
    var bucketStart: Instant = Instant.EPOCH

    @Column(nullable = false, length = 96)
    var metric: String = ""

    @Column(nullable = false, length = 160)
    var dimension: String = ""

    // Backticks make Hibernate emit a quoted "value"; VALUE is reserved on H2.
    @Column(name = "`value`", nullable = false)
    var value: Double = 0.0
}
