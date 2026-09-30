package com.afrithecus.brainbox.api.subscription

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.report.ReportProperties
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** How much of a class or grade sits on an active Explorer-or-higher plan. */
data class SubscriptionCoverage(
    val total: Int,
    val covered: Int,
    val ratio: Double,
    val threshold: Double,
    val allowed: Boolean,
)

/**
 * Mass-download coverage gate (docs/ongoing/product_ops_roadmap.md item 1).
 *
 * A learner counts as covered when they hold an ACTIVE EXPLORER or PRO plan, or a
 * linked parent does. PRO counts as covered. The roadmap records the assumption
 * that either the learner or the payer may hold the plan; if that changes, only
 * [covered] below needs to change.
 *
 * There was previously NO aggregate by class or grade anywhere in the API, so this
 * is the first one.
 */
@Service
class SubscriptionCoverageService(
    private val memberships: ClassMembershipRepository,
    private val users: UserRepository,
    private val subscriptions: SubscriptionRepository,
    private val properties: ReportProperties,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun forClass(classId: UUID): SubscriptionCoverage =
        coverage(memberships.findAllByClassId(classId).map { it.studentId })

    @Transactional(readOnly = true)
    fun forGrade(schoolId: UUID, gradeLevel: String): SubscriptionCoverage =
        coverage(
            users.findAllBySchoolIdAndGradeLevelAndRole(schoolId, gradeLevel, Role.STUDENT).map { it.id }
        )

    /** Coverage of an explicit learner set (a multi-student bulk job). */
    @Transactional(readOnly = true)
    fun forStudents(studentIds: List<UUID>): SubscriptionCoverage = coverage(studentIds.distinct())

    private fun coverage(learners: List<UUID>): SubscriptionCoverage {
        val threshold = properties.explorerCoverageThreshold
        if (learners.isEmpty()) return SubscriptionCoverage(0, 0, 0.0, threshold, false)

        val byId = users.findAllById(learners).associateBy { it.id }

        // One query for every candidate account: each learner plus their payer.
        val candidates = buildSet {
            learners.forEach { id ->
                add(id)
                byId[id]?.parentUserId?.let { add(it) }
            }
        }
        val coveredAccounts = subscriptions.findAllByUserIdIn(candidates)
            .filter { isActiveExplorer(it) }
            .map { it.userId }
            .toSet()

        val covered = learners.count { id ->
            id in coveredAccounts || byId[id]?.parentUserId?.let { it in coveredAccounts } == true
        }
        val ratio = covered.toDouble() / learners.size
        return SubscriptionCoverage(learners.size, covered, ratio, threshold, ratio >= threshold)
    }

    private fun isActiveExplorer(row: SubscriptionEntity): Boolean {
        if (row.status != SubscriptionStatus.ACTIVE) return false
        if (row.tier != SubscriptionTier.EXPLORER && row.tier != SubscriptionTier.PRO) return false
        val expiry = row.expiryDate ?: return true
        return expiry.isAfter(clock.instant())
    }
}
