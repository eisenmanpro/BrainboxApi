package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.sms.BeemSmsGateway
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Beem Africa phone-verification delivery (pilot/fallback provider, docs/phone_verification.md).
 * The wire contract lives in [BeemSmsGateway], shared with the attendance SMS channel; single
 * attempt only, for the same duplicate-code reason as the other senders.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.auth.phone-verification",
    name = ["provider"],
    havingValue = "beem",
)
class BeemSmsNotifier(
    private val properties: PhoneVerificationProperties,
    mapper: ObjectMapper,
) : PhoneVerificationNotifier {

    private val gateway = BeemSmsGateway(
        properties.beem,
        properties.connectTimeout,
        properties.requestTimeout,
        mapper,
    )

    override fun send(user: UserEntity, code: String) {
        val config = properties.beem
        if (!config.configured) {
            throw IllegalStateException("Beem credentials are not configured")
        }
        if (config.senderId.isBlank()) {
            throw IllegalStateException("Beem sender id is not configured")
        }
        val to = PhoneNumbers.normalize(user.phoneNumber.orEmpty())
            ?: throw IllegalStateException("Account has no valid phone number to verify")
        gateway.send(to, phoneVerificationMessage(properties, code))
    }
}
