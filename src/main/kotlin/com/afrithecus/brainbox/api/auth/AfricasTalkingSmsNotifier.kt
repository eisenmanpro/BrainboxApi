package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.sms.AfricasTalkingSmsGateway
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Africa's Talking phone-verification delivery (fallback provider, docs/phone_verification.md).
 * The wire contract lives in [AfricasTalkingSmsGateway], shared with the attendance SMS channel;
 * a single attempt only: retrying an OTP send would deliver (and bill) a second code.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.auth.phone-verification",
    name = ["provider"],
    havingValue = "africastalking",
)
class AfricasTalkingSmsNotifier(
    private val properties: PhoneVerificationProperties,
    mapper: ObjectMapper,
) : PhoneVerificationNotifier {

    private val gateway = AfricasTalkingSmsGateway(
        properties.africastalking,
        properties.connectTimeout,
        properties.requestTimeout,
        mapper,
    )

    override fun send(user: UserEntity, code: String) {
        if (!properties.africastalking.configured) {
            throw IllegalStateException("Africa's Talking credentials are not configured")
        }
        val to = PhoneNumbers.normalize(user.phoneNumber.orEmpty())
            ?: throw IllegalStateException("Account has no valid phone number to verify")
        gateway.send(to, phoneVerificationMessage(properties, code))
    }
}
