package com.afrithecus.brainbox.api.subscription

import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** Client-facing subscription view (doc 01 §1.1 / §4.3). */
data class SubscriptionView(
    val userId: UUID,
    val status: String,
    val tier: String,
    val expiryDate: Long?,
    val totalPaid: Int,
)

/**
 * One authoritative subscription row per user. Expiry is ALWAYS recomputed
 * server-side on read: ACTIVE/EXPIRED derive from the stored expiry date, never
 * from a client-supplied value (doc 01 §4.3 obligations 3-4).
 */
@Service
class SubscriptionService(
    private val repository: SubscriptionRepository,
    private val clock: Clock,
) {

    @Transactional
    fun ensure(userId: UUID): SubscriptionEntity =
        repository.findByUserId(userId) ?: repository.save(
            SubscriptionEntity().apply {
                this.userId = userId
                status = SubscriptionStatus.NONE
                tier = SubscriptionTier.BASE
            }
        )

    @Transactional
    fun view(userId: UUID): SubscriptionView {
        val row = ensure(userId)
        val now = clock.instant()
        val expired = row.tier != SubscriptionTier.BASE &&
            row.expiryDate != null && row.expiryDate!!.isBefore(now)
        if (expired && row.status != SubscriptionStatus.EXPIRED) {
            row.status = SubscriptionStatus.EXPIRED
            repository.save(row)
        }
        return toView(row)
    }

    fun toView(row: SubscriptionEntity): SubscriptionView = SubscriptionView(
        userId = row.userId,
        status = row.status.name,
        tier = row.tier.name,
        expiryDate = row.expiryDate?.toEpochMilli(),
        totalPaid = row.totalPaid,
    )
}
