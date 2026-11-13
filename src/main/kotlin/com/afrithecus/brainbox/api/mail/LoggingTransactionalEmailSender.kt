package com.afrithecus.brainbox.api.mail

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Development transport: records that a message would be delivered and, only when
 * app.mail.log-body=true, prints the body so a developer can read the code. Nothing leaves the
 * machine, so [configured] is false and callers that require real delivery can refuse.
 */
@Component
@ConditionalOnProperty(prefix = "app.mail", name = ["provider"], havingValue = "logging", matchIfMissing = true)
class LoggingTransactionalEmailSender(private val properties: MailProperties) : TransactionalEmailSender {

    override val configured: Boolean = false

    override fun send(to: String, subject: String, textBody: String) {
        if (properties.logBody) {
            log.warn("Email (logging provider) to {} | subject={} | body={}", to, subject, textBody)
        } else {
            log.info("Email suppressed (logging provider) to {} | subject={}", to, subject)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(LoggingTransactionalEmailSender::class.java)
    }
}
