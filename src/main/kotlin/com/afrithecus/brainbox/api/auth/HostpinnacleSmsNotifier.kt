package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.sms.HostpinnacleSmsGateway
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * HostPinnacle phone-verification delivery (default provider, docs/phone_verification.md).
 * The wire contract lives in [HostpinnacleSmsGateway], shared with the attendance SMS channel;
 * this class only refuses an incomplete configuration and composes the one OTP message body.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.auth.phone-verification",
    name = ["provider"],
    havingValue = "hostpinnacle",
)
class HostpinnacleSmsNotifier(
    private val properties: PhoneVerificationProperties,
    mapper: ObjectMapper,
) : PhoneVerificationNotifier {

    private val gateway = HostpinnacleSmsGateway(
        properties.hostpinnacle,
        properties.connectTimeout,
        properties.requestTimeout,
        mapper,
    )

    override fun send(user: UserEntity, code: String) {
        val config = properties.hostpinnacle
        if (!config.configured) {
            throw IllegalStateException("HostPinnacle credentials are not configured")
        }
        if (config.senderId.isBlank()) {
            throw IllegalStateException("HostPinnacle sender id is not configured")
        }
        val to = PhoneNumbers.normalize(user.phoneNumber.orEmpty())
            ?: throw IllegalStateException("Account has no valid phone number to verify")
        gateway.send(to, phoneVerificationMessage(properties, code))
    }
}
