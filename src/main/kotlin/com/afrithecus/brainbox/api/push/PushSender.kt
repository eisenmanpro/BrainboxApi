package com.afrithecus.brainbox.api.push

/**
 * Delivers a [PushMessage] to FCM registration tokens. Implementations return the
 * tokens FCM reported as gone so the caller can prune them.
 */
interface PushSender {
    fun send(tokens: List<String>, message: PushMessage): List<String>
}
