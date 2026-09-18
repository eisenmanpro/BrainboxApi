package com.afrithecus.brainbox.api.payments.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * One STK push attempt. [providerRef] is the provider invoice id and is the
 * idempotency key for activation: a replayed webhook finds the same row and an
 * already-SUCCESS row is never activated twice.
 */
@Entity
@Table(name = "payment_transactions")
class PaymentTransactionEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: java.util.UUID = java.util.UUID.randomUUID()

    /** The tier being bought: EXPLORER or PRO. */
    @Column(nullable = false, length = 16)
    var tier: String = ""

    @Column(nullable = false)
    var amount: Int = 0

    @Column(nullable = false, length = 8)
    var currency: String = "KES"

    @Column(name = "phone_number", nullable = false, length = 16)
    var phoneNumber: String = ""

    @Column(nullable = false, length = 24)
    var provider: String = "INTASEND"

    @Column(name = "provider_ref", length = 64)
    var providerRef: String? = null

    @Column(nullable = false, length = 16)
    var status: String = "PENDING"

    @Column(name = "failure_reason", columnDefinition = "text")
    var failureReason: String? = null

    @Column(name = "client_request_id", length = 64)
    var clientRequestId: String? = null
}
