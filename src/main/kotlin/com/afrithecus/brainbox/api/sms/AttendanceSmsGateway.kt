package com.afrithecus.brainbox.api.sms

import com.afrithecus.brainbox.api.attendance.AttendanceSmsProperties
import com.afrithecus.brainbox.api.auth.PhoneVerificationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Resolves the attendance channel's gateway. Credentials are shared with phone verification
 * (one HostPinnacle account), but the provider can differ so attendance can be moved to the
 * fallback without touching OTP, or vice versa. Built per call because the configuration is
 * immutable at boot and the gateway is cheap to assemble.
 */
@Component
class AttendanceSmsGateway(
    private val properties: PhoneVerificationProperties,
    private val attendance: AttendanceSmsProperties,
    private val mapper: ObjectMapper,
) {

    fun gateway(): SmsGateway =
        SmsGatewayFactory.forProvider(attendance.provider.ifBlank { properties.provider }, properties, mapper)
}
