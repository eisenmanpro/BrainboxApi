package com.afrithecus.brainbox.api.sms

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * P4.4: ordered delivery chain for the attendance channel (HostPinnacle -> Beem/Africa's Talking
 * on failure), with a per-provider circuit breaker and metrics.
 *
 * The chain is only tried for a provider that threw. A provider that returned a message id is
 * never called again, so a failure after the provider accepted the message (for example a
 * read timeout) can still duplicate a message; that is a known trade-off of a fallback chain and
 * is why the opt-in fallback list is empty by default. OTP deliberately does not use this chain:
 * a second verification code is never safe.
 *
 * Circuit state is per process and in memory: a node that has just started has closed circuits,
 * which is the safe direction (it may retry a struggling provider once).
 */
class SmsGatewayChain(
    private val gateways: List<SmsGateway>,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    failureThreshold: Int = 3,
    openFor: Duration = Duration.ofSeconds(120),
) {

    init {
        require(gateways.isNotEmpty()) { "SMS gateway chain needs at least one provider" }
    }

    /** The provider recorded on a dispatch before delivery; the actual one may differ. */
    val primaryProvider: String get() = gateways.first().provider

    /** Provider order, for logs and diagnostics. */
    val providers: List<String> get() = gateways.map { it.provider }

    private val failureThreshold: Int = failureThreshold.coerceAtLeast(1)
    private val openFor: Duration = openFor.coerceAtLeast(Duration.ofSeconds(1))
    private val breakers = ConcurrentHashMap<String, Breaker>()

    data class Delivery(val provider: String, val messageId: String?)

    private data class Breaker(var failures: Int = 0, var openUntil: Instant? = null)

    /**
     * Tries each provider in order until one accepts the message. Throws when every provider is
     * either open or failed; the exception names providers and reasons but never the phone number.
     */
    fun deliver(to: String, message: String): Delivery {
        val reasons = mutableListOf<String>()
        for (gateway in gateways) {
            val provider = gateway.provider
            if (isOpen(provider)) {
                meterRegistry.counter("brainbox.sms.skipped", "provider", provider, "reason", "circuit_open")
                    .increment()
                reasons += provider + ": circuit open"
                continue
            }
            val messageId = try {
                gateway.send(to, message)
            } catch (failure: Exception) {
                recordFailure(provider)
                meterRegistry.counter("brainbox.sms.failures", "provider", provider).increment()
                reasons += provider + ": " + (failure.message ?: failure.javaClass.simpleName)
                log.warn("SMS provider {} failed, trying the next provider ({})", provider, failure.message)
                continue
            }
            recordSuccess(provider)
            meterRegistry.counter("brainbox.sms.sent", "provider", provider).increment()
            return Delivery(provider, messageId)
        }
        throw IllegalStateException("All SMS providers failed: " + reasons.joinToString("; "))
    }

    private fun isOpen(provider: String): Boolean {
        val breaker = breakers[provider] ?: return false
        val openUntil = breaker.openUntil ?: return false
        if (clock.instant().isBefore(openUntil)) return true
        // Half-open: let one attempt through.
        breaker.openUntil = null
        breaker.failures = 0
        return false
    }

    private fun recordFailure(provider: String) {
        val breaker = breakers.computeIfAbsent(provider) { Breaker() }
        breaker.failures += 1
        if (breaker.failures >= failureThreshold) {
            breaker.openUntil = clock.instant().plus(openFor)
            meterRegistry.counter("brainbox.sms.circuit", "provider", provider, "state", "open").increment()
            log.warn(
                "SMS provider {} circuit opened after {} failures for {}s",
                provider,
                breaker.failures,
                openFor.seconds,
            )
        }
    }

    private fun recordSuccess(provider: String) {
        breakers.remove(provider)
    }

    private companion object {
        val log = LoggerFactory.getLogger(SmsGatewayChain::class.java)
    }
}
