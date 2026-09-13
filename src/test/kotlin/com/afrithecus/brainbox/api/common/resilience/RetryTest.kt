package com.afrithecus.brainbox.api.common.resilience

import org.junit.jupiter.api.Test

class RetryTest {

    @Test
    fun `returns the first successful result without sleeping`() {
        var calls = 0
        val value = Retry.withBackoff(initialDelayMillis = 1) { calls++; "ok" }
        check(value == "ok" && calls == 1)
    }

    @Test
    fun `retries a transient failure and eventually succeeds`() {
        var calls = 0
        val value = Retry.withBackoff(attempts = 3, initialDelayMillis = 1) {
            calls++
            if (calls < 3) throw IllegalStateException("transient")
            "ok"
        }
        check(value == "ok" && calls == 3)
    }

    @Test
    fun `rethrows after exhausting the attempt budget`() {
        var calls = 0
        val error = runCatching {
            Retry.withBackoff(attempts = 2, initialDelayMillis = 1) {
                calls++
                throw IllegalStateException("boom")
            }
        }.exceptionOrNull()
        check(error is IllegalStateException && error.message == "boom")
        check(calls == 2)
    }

    @Test
    fun `a non-retryable failure fails immediately`() {
        var calls = 0
        val error = runCatching {
            Retry.withBackoff(attempts = 3, initialDelayMillis = 1, isRetryable = { false }) {
                calls++
                throw IllegalArgumentException("terminal")
            }
        }.exceptionOrNull()
        check(error is IllegalArgumentException)
        check(calls == 1)
    }
}
