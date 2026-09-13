package com.afrithecus.brainbox.api.push

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Default sender when FCM is not configured. It records the intent at debug level
 * and drops the message, so a deployment without credentials still writes the in-app
 * notification and nothing else fails.
 */
@Component
@ConditionalOnProperty(name = ["app.push.fcm.enabled"], havingValue = "false", matchIfMissing = true)
class LoggingPushSender : PushSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(tokens: List<String>, message: PushMessage): List<String> {
        log.debug("FCM disabled; skipping {} push to {} device(s): {}", message.type, tokens.size, message.title)
        return emptyList()
    }
}
