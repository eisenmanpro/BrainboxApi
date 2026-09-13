package com.afrithecus.brainbox.api.push

/** Pure FCM HTTP v1 payload shaping, kept separate so it is unit-testable. */
object FcmPayloads {

    fun data(message: PushMessage): Map<String, String> {
        val data = LinkedHashMap<String, String>()
        data["title"] = message.title
        data["message"] = message.message
        message.type?.takeIf { it.isNotBlank() }?.let { data["type"] = it }
        message.actionRoute?.takeIf { it.isNotBlank() }?.let { data["actionRoute"] = it }
        message.actionLabel?.takeIf { it.isNotBlank() }?.let { data["actionLabel"] = it }
        message.urgency?.takeIf { it.isNotBlank() }?.let { data["urgency"] = it }
        message.metadata.forEach { (key, value) -> if (value.isNotBlank()) data[key] = value }
        return data
    }

    fun requestBody(token: String, message: PushMessage, channelId: String): Map<String, Any> {
        val highPriority = message.urgency == "URGENT" || message.urgency == "HIGH"
        val android = linkedMapOf<String, Any>(
            "priority" to if (highPriority) "high" else "normal",
            "notification" to mapOf("channel_id" to channelId),
        )
        return mapOf(
            "message" to linkedMapOf<String, Any>(
                "token" to token,
                "data" to data(message),
                "notification" to mapOf("title" to message.title, "body" to message.message),
                "android" to android,
            ),
        )
    }
}
