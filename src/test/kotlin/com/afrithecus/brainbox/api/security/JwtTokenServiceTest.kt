package com.afrithecus.brainbox.api.security

import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.security.oauth2.jwt.JwtException

class JwtTokenServiceTest {

    private val secret = Base64.getEncoder().encodeToString(
        "0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)
    )
    private val clock = Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC)
    private val props = JwtProperties(secret = secret, issuer = "brainbox-api")
    private val service = JwtTokenService(props, clock)

    @Test
    fun `issues and parses an access token with claims`() {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val token = service.issueAccessToken(
            userId = userId,
            role = Role.TEACHER,
            subRole = SubRole.CTEACHER,
            sessionId = sessionId,
            deviceId = "device-1",
        )

        val claims = service.parseAccessToken(token)
        assertEquals(userId, claims.userId)
        assertEquals(Role.TEACHER, claims.role)
        assertEquals(SubRole.CTEACHER, claims.subRole)
        assertEquals(sessionId, claims.sessionId)
        assertEquals("device-1", claims.deviceId)
    }

    @Test
    fun `omits optional claims when absent`() {
        val userId = UUID.randomUUID()
        val token = service.issueAccessToken(userId = userId, role = Role.STUDENT)
        val claims = service.parseAccessToken(token)
        assertEquals(Role.STUDENT, claims.role)
        assertNull(claims.subRole)
        assertNull(claims.sessionId)
        assertNull(claims.deviceId)
    }

    @Test
    fun `rejects a tampered token`() {
        val userId = UUID.randomUUID()
        val token = service.issueAccessToken(userId = userId, role = Role.ADMIN)
        val chars = token.toCharArray()
        chars[chars.size / 2] = if (chars[chars.size / 2] == 'a') 'b' else 'a'
        assertFailsWith<JwtException> { service.parseAccessToken(String(chars)) }
    }

    @Test
    fun `rejects a token from an unexpected issuer`() {
        val otherProps = JwtProperties(secret = secret, issuer = "other-service")
        val other = JwtTokenService(otherProps, clock)
        val token = other.issueAccessToken(userId = UUID.randomUUID(), role = Role.STUDENT)
        assertFailsWith<JwtException> { service.parseAccessToken(token) }
    }

    @Test
    fun `refresh tokens are opaque, unique and hashed`() {
        val first = service.newRefreshToken()
        val second = service.newRefreshToken()
        assertTrue(first.raw != second.raw)
        assertTrue(first.raw.length >= 40)
        assertEquals(first.hash, TokenHash.sha256Hex(first.raw))
        assertTrue(first.hash.length == 64)
        assertTrue(first.hash.all { it.isDigit() || it in 'a'..'f' })
    }
}
