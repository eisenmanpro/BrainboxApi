package com.afrithecus.brainbox.api.common.error

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.mock.web.MockHttpServletRequest

class GlobalExceptionHandlerTest {

    private val registry = SimpleMeterRegistry()
    private val handler = GlobalExceptionHandler(registry)

    @Test
    fun `an optimistic lock failure becomes a counted 409`() {
        val request = MockHttpServletRequest("PUT", "/teacher/gradebook/entry-1")
        val response = handler.handleOptimisticLock(OptimisticLockingFailureException("stale"), request)
        check(response.statusCode.value() == 409)
        check(response.body?.error == "CONFLICT")
        check(registry.counter("brainbox.conflicts", "type", "optimistic").count() == 1.0)
    }
}
