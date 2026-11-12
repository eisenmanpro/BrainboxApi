package com.afrithecus.brainbox.api.sms

import com.afrithecus.brainbox.api.attendance.AttendanceSmsProperties
import com.afrithecus.brainbox.api.auth.PhoneVerificationProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration

/**
 * Resolves the attendance channel's gateway chain (Phase 4, P4.4). Credentials are shared with
 * phone verification (one HostPinnacle account), but the provider can differ so attendance can be
 * moved to the fallback without touching OTP, or vice versa. The primary is followed by the
 * configured fallbacks; a blank primary inherits the phone-verification provider.
 */
@Component
class AttendanceSmsGateway(
    private val properties: PhoneVerificationProperties,
    private val attendance: AttendanceSmsProperties,
    private val mapper: ObjectMapper,
    meterRegistry: MeterRegistry,
    clock: Clock,
) {

    private val chain: SmsGatewayChain by lazy {
        val primary = attendance.provider.ifBlank { properties.provider }
        val names = (listOf(primary) + attendance.fallbackProviders)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        SmsGatewayChain(
            gateways = names.map { SmsGatewayFactory.forProvider(it, properties, mapper) },
            meterRegistry = meterRegistry,
            clock = clock,
            failureThreshold = attendance.circuitFailureThreshold,
            openFor = Duration.ofSeconds(attendance.circuitOpenSeconds.coerceAtLeast(1)),
        )
    }

    fun chain(): SmsGatewayChain = chain
}
