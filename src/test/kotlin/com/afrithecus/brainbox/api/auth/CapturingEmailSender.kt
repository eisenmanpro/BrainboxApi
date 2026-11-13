package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.mail.TransactionalEmailSender
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentHashMap

/**
 * Test transport: records the message instead of delivering it, so a test can read the one-time
 * code the service generated. Production uses the Mailjet SMTP sender.
 */
class CapturingEmailSender : TransactionalEmailSender {

    private val messages = ConcurrentHashMap<String, MutableList<String>>()

    override val configured: Boolean = true

    override fun send(to: String, subject: String, textBody: String) {
        messages.computeIfAbsent(to) { mutableListOf() }.add(textBody)
    }

    fun lastBodyFor(email: String): String =
        messages[email]?.lastOrNull() ?: error("No email captured for " + email)

    fun codeFor(email: String): String {
        val body = lastBodyFor(email)
        val match = Regex("\\b(\\d{6})\\b").find(body)
            ?: error("No 6-digit code in the message for " + email)
        return match.groupValues[1]
    }

    fun clear() = messages.clear()
}

@TestConfiguration
class CapturingEmailConfig {

    @Bean
    @Primary
    fun capturingEmailSender(): CapturingEmailSender = CapturingEmailSender()
}
