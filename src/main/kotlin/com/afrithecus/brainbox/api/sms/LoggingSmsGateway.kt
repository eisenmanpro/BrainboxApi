package com.afrithecus.brainbox.api.sms

import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

/**
 * Development delivery (provider "logging"): records that a message would have been sent, with
 * the destination masked so no phone number lands in logs. Returns no provider id.
 */
class LoggingSmsGateway : SmsGateway {

    override val provider: String = "logging"

    override fun send(to: String, message: String): String? {
        log.info(
            "SMS (logging provider) to {} ({} chars): not delivered",
            mask(to),
            message.toByteArray(StandardCharsets.UTF_8).size,
        )
        return null
    }

    private fun mask(phone: String): String {
        val digits = phone.filter(Char::isDigit)
        return if (digits.length <= 4) "***" else "***" + digits.takeLast(4)
    }

    private companion object {
        val log = LoggerFactory.getLogger(LoggingSmsGateway::class.java)
    }
}
