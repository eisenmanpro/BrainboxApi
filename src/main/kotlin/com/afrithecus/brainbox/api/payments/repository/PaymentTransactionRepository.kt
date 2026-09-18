package com.afrithecus.brainbox.api.payments.repository

import com.afrithecus.brainbox.api.payments.entity.PaymentTransactionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PaymentTransactionRepository : JpaRepository<PaymentTransactionEntity, UUID> {

    fun findByUserIdAndClientRequestId(userId: UUID, clientRequestId: String): PaymentTransactionEntity?

    fun findByProviderRef(providerRef: String): PaymentTransactionEntity?

    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<PaymentTransactionEntity>
}
