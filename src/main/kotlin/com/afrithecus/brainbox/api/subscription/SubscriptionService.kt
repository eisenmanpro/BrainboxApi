package com.afrithecus.brainbox.api.subscription

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionHistoryRepository
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import com.afrithecus.brainbox.api.subscription.web.SubscriptionHistoryPayload
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
    val mpesaTransactionId: String? = null,
)

/**
 * One authoritative subscription row per user. Expiry is ALWAYS recomputed
 * server-side on read: ACTIVE/EXPIRED derive from the stored expiry date, never
 * from a client-supplied value (doc 01 §4.3 obligations 3-4).
 */
@Service
class SubscriptionService(
    private val repository: SubscriptionRepository,
    private val historyRepository: SubscriptionHistoryRepository,
    private val userRepository: UserRepository,
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

    /** Self, a linked parent, or a coordinator/ICT/admin may read a subscription. */
    @Transactional(readOnly = true)
    fun viewFor(actor: CurrentUser, targetUserId: UUID): SubscriptionView {
        requireAccess(actor, targetUserId)
        return view(targetUserId)
    }

    @Transactional(readOnly = true)
    fun historyFor(actor: CurrentUser, targetUserId: UUID): List<SubscriptionHistoryPayload> {
        requireAccess(actor, targetUserId)
        return history(targetUserId)
    }

    @Transactional(readOnly = true)
    fun history(userId: UUID): List<SubscriptionHistoryPayload> =
        historyRepository.findAllByUserIdOrderByCreatedAtDesc(userId).map { row ->
            SubscriptionHistoryPayload(
                id = row.id.toString(),
                userId = row.userId.toString(),
                action = row.action,
                tier = row.tier,
                amount = row.amount,
                transactionId = row.transactionId,
                timestamp = row.createdAt.toEpochMilli(),
            )
        }

    fun toView(row: SubscriptionEntity): SubscriptionView = SubscriptionView(
        userId = row.userId,
        status = row.status.name,
        tier = row.tier.name,
        expiryDate = row.expiryDate?.toEpochMilli(),
        totalPaid = row.totalPaid,
        mpesaTransactionId = row.mpesaTransactionId,
    )

    private fun requireAccess(actor: CurrentUser, targetUserId: UUID) {
        if (actor.userId == targetUserId) return
        val user = userRepository.findById(actor.userId).orElse(null) ?: throw notFound("User not found")
        if (user.role == Role.ADMIN) return
        if (user.role == Role.PARENT && userRepository.findByParentUserId(user.id).any { it.id == targetUserId }) return
        if (user.role == Role.TEACHER &&
            (user.subRole == SubRole.GRADE_COORDINATOR || user.subRole == SubRole.ICT_ADMIN)
        ) {
            return
        }
        throw ApiException(ApiErrorCode.FORBIDDEN, "Not your subscription")
    }
}
