package com.afrithecus.brainbox.api.push

import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Fans an in-app notification out to the recipient's registered devices. The
 * network send runs after the surrounding transaction commits so a slow FCM call
 * never holds the database transaction open.
 */
@Service
class PushFanoutService(
    private val deviceTokens: DeviceTokenStore,
    private val sender: PushSender,
) {

    fun dispatch(userId: UUID, message: PushMessage) {
        val tokens = runCatching { deviceTokens.tokensFor(userId) }.getOrDefault(emptyList())
        if (tokens.isEmpty()) return
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = deliver(userId, tokens, message)
            })
        } else {
            deliver(userId, tokens, message)
        }
    }

    private fun deliver(userId: UUID, tokens: List<String>, message: PushMessage) {
        val invalid = runCatching { sender.send(tokens, message) }.getOrDefault(emptyList())
        if (invalid.isNotEmpty()) runCatching { deviceTokens.deleteTokens(userId, invalid) }
    }
}
