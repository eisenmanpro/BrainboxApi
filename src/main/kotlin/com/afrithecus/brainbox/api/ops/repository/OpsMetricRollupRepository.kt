package com.afrithecus.brainbox.api.ops.repository

import com.afrithecus.brainbox.api.ops.entity.OpsMetricRollupEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface OpsMetricRollupRepository : JpaRepository<OpsMetricRollupEntity, UUID> {

    /** Idempotent upsert lookup: the single row for one (hour, metric, dimension). */
    fun findByBucketStartAndMetricAndDimension(
        bucketStart: Instant,
        metric: String,
        dimension: String,
    ): OpsMetricRollupEntity?

    /** Retention purge: removes every rollup strictly older than [cutoff]. */
    fun deleteByBucketStartBefore(cutoff: Instant): Long

    /** The newest bucket written for a metric, used for the "latest rolled-up" summary. */
    fun findFirstByMetricOrderByBucketStartDesc(metric: String): OpsMetricRollupEntity?

    /** The newest bucket written for any metric, exposed as the summary's rollup watermark. */
    fun findFirstByOrderByBucketStartDesc(): OpsMetricRollupEntity?

    /** Every dimension of one metric at one bucket, used for the reason-mix snapshot. */
    fun findAllByMetricAndBucketStartOrderByDimensionAsc(
        metric: String,
        bucketStart: Instant,
    ): List<OpsMetricRollupEntity>

    /**
     * The ordered points of one series, optionally filtered to a single dimension,
     * in [from, to). [pageable] bounds the result so a frontend cannot pull an
     * unbounded series.
     */
    @Query(
        "SELECT r FROM OpsMetricRollupEntity r WHERE r.metric = :metric " +
            "AND (:dimension IS NULL OR r.dimension = :dimension) " +
            "AND r.bucketStart >= :from AND r.bucketStart < :to " +
            "ORDER BY r.bucketStart ASC"
    )
    fun findSeries(
        @Param("metric") metric: String,
        @Param("dimension") dimension: String?,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        pageable: Pageable,
    ): List<OpsMetricRollupEntity>
}
