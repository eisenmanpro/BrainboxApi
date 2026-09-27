package com.afrithecus.brainbox.api.common.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one place an absolute public URL is composed. `app.public-base-url` is the CDN
 * knob: set it and every link the API hands out points there; leave it empty and the
 * request's own base is used, exactly as before.
 */
class PublicUrlBuilderTests {

    @BeforeEach
    fun setUp() {
        val request = MockHttpServletRequest().apply {
            scheme = "https"
            serverName = "api.brainbox.co.ke"
            serverPort = 443
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Test
    fun anEmptyConfigurationKeepsTheRequestBase() {
        val urls = PublicUrlBuilder("")
        assertFalse(urls.hasPublicBaseUrl())
        assertEquals("https://api.brainbox.co.ke", urls.baseUrl())
        assertEquals("https://api.brainbox.co.ke/media/abc.png", urls.mediaUrl("abc.png"))
    }

    @Test
    fun aConfiguredBaseWinsAndIsNormalised() {
        // Surrounding whitespace and a trailing slash must not produce a double slash,
        // which is what a CDN origin or a signature check would reject.
        val urls = PublicUrlBuilder("  https://cdn.brainbox.co.ke/  ")
        assertTrue(urls.hasPublicBaseUrl())
        assertEquals("https://cdn.brainbox.co.ke", urls.baseUrl())
        assertEquals("https://cdn.brainbox.co.ke/media/abc.png", urls.mediaUrl("abc.png"))
    }

    @Test
    fun anExplicitBaseOverridesBoth() {
        // The confirm path can still be handed a base explicitly (tests, internal calls).
        val urls = PublicUrlBuilder("https://cdn.brainbox.co.ke")
        assertEquals("https://internal.example", urls.baseUrl("https://internal.example/"))
    }
}
