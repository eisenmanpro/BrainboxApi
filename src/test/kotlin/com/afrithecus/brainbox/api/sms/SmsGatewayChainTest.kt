package com.afrithecus.brainbox.api.sms

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * P4.4: the fallback chain only moves to the next provider after a failure, opens a circuit after
 * repeated failures, and names every provider when none can deliver. No phone number is logged.
 */
class SmsGatewayChainTest {

    private val registry = SimpleMeterRegistry()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneOffset.UTC)

    private class FakeGateway(
        override val provider: String,
        private val failing: Boolean,
    ) : SmsGateway {
        var calls = 0
        override fun send(to: String, message: String): String? {
            calls += 1
            if (failing) throw IllegalStateException(provider + " is down")
            return provider + "-message-id"
        }
    }

    private fun chain(
        vararg gateways: SmsGateway,
        threshold: Int = 3,
        openSeconds: Long = 120,
    ) = SmsGatewayChain(
        gateways = gateways.toList(),
        meterRegistry = registry,
        clock = clock,
        failureThreshold = threshold,
        openFor = Duration.ofSeconds(openSeconds),
    )

    @Test
    fun `a primary failure is retried on the fallback and the fallback is recorded`() {
        val primary = FakeGateway("hostpinnacle", failing = true)
        val fallback = FakeGateway("beem", failing = false)
        val delivery = chain(primary, fallback).deliver("0712345678", "test")
        check(delivery.provider == "beem")
        check(delivery.messageId == "beem-message-id")
        check(primary.calls == 1)
        check(fallback.calls == 1)
        check(registry.counter("brainbox.sms.failures", "provider", "hostpinnacle").count() == 1.0)
        check(registry.counter("brainbox.sms.sent", "provider", "beem").count() == 1.0)
    }

    @Test
    fun `a circuit opens after the threshold and skips the failing provider`() {
        val primary = FakeGateway("hostpinnacle", failing = true)
        val fallback = FakeGateway("beem", failing = false)
        val chain = chain(primary, fallback, threshold = 2)

        chain.deliver("0712345678", "one")
        chain.deliver("0712345678", "two")
        check(primary.calls == 2)

        val third = chain.deliver("0712345678", "three")
        check(third.provider == "beem")
        check(primary.calls == 2) { "an open circuit must not call the provider again" }
        check(registry.counter("brainbox.sms.skipped", "provider", "hostpinnacle", "reason", "circuit_open").count() == 1.0)
    }

    @Test
    fun `all providers failing surfaces every reason`() {
        val failure = runCatching {
            chain(FakeGateway("hostpinnacle", failing = true), FakeGateway("beem", failing = true))
                .deliver("0712345678", "test")
        }.exceptionOrNull()
        check(failure is IllegalStateException)
        check(failure.message!!.contains("hostpinnacle"))
        check(failure.message!!.contains("beem"))
    }

    @Test
    fun `a successful delivery resets the provider failure count`() {
        val flaky = object : SmsGateway {
            override val provider = "hostpinnacle"
            var calls = 0
            override fun send(to: String, message: String): String? {
                calls += 1
                if (calls == 1) throw IllegalStateException("transient")
                return "ok"
            }
        }
        val chain = chain(flaky, FakeGateway("beem", failing = false), threshold = 2)
        check(chain.deliver("0712345678", "one").provider == "beem")
        check(chain.deliver("0712345678", "two").provider == "hostpinnacle")
        check(chain.deliver("0712345678", "three").provider == "hostpinnacle")
    }
}
