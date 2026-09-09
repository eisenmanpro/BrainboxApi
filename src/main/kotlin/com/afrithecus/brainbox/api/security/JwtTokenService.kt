package com.afrithecus.brainbox.api.security

import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** Validated payload extracted from an access token. */
data class AccessTokenClaims(
    val userId: UUID,
    val role: Role,
    val subRole: SubRole?,
    val sessionId: UUID?,
    val deviceId: String?,
)

/** A freshly generated opaque refresh token plus its stored digest. */
data class NewRefreshToken(val raw: String, val hash: String)

/**
 * Issues and validates access JWTs (24h) and opaque refresh tokens stored as
 * SHA-256 digests (doc 01 §1.5, doc 11 §1).
 */
@Service
class JwtTokenService(
    private val properties: JwtProperties,
    private val clock: Clock,
) {
    private val key: SecretKeySpec by lazy {
        val bytes = Base64.getDecoder().decode(properties.secret)
        require(bytes.size >= 32) { "app.security.jwt.secret must decode to at least 32 bytes" }
        SecretKeySpec(bytes, "HmacSHA256")
    }

    private val encoder: JwtEncoder by lazy {
        NimbusJwtEncoder.withSecretKey(key).build()
    }

    private val decoder: JwtDecoder by lazy {
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
    }

    fun issueAccessToken(
        userId: UUID,
        role: Role,
        subRole: SubRole? = null,
        sessionId: UUID? = null,
        deviceId: String? = null,
    ): String {
        val now = clock.instant()
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .issuedAt(now)
            .expiresAt(now.plus(properties.accessTokenTtl))
            .subject(userId.toString())
            .id(UUID.randomUUID().toString())
            .claim(CLAIM_ROLE, role.name)
            .apply { if (subRole != null) claim(CLAIM_SUB_ROLE, subRole.name) }
            .apply { if (sessionId != null) claim(CLAIM_SESSION, sessionId.toString()) }
            .apply { if (deviceId != null) claim(CLAIM_DEVICE, deviceId) }
            .build()
        return encoder.encode(JwtEncoderParameters.from(claims)).tokenValue
    }

    /**
     * Parses and validates an access token (signature, expiry, issuer).
     * @throws JwtException when the token is invalid or expired.
     */
    fun parseAccessToken(token: String): AccessTokenClaims {
        val jwt: Jwt = decoder.decode(token)
        val issuer = jwt.getClaimAsString(ISS_CLAIM)
        if (issuer != null && issuer != properties.issuer) {
            throw JwtException("Unexpected issuer")
        }
        val userId = jwt.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw JwtException("Missing subject")
        val role = runCatching { Role.valueOf(jwt.getClaimAsString(CLAIM_ROLE) ?: "") }.getOrNull()
            ?: throw JwtException("Missing or invalid role claim")
        val subRole = jwt.getClaimAsString(CLAIM_SUB_ROLE)?.let {
            runCatching { SubRole.valueOf(it) }.getOrNull()
        }
        val sessionId = jwt.getClaimAsString(CLAIM_SESSION)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        return AccessTokenClaims(
            userId = userId,
            role = role,
            subRole = subRole,
            sessionId = sessionId,
            deviceId = jwt.getClaimAsString(CLAIM_DEVICE),
        )
    }

    /** Generates an opaque refresh token (raw value returned once, hash persisted). */
    fun newRefreshToken(): NewRefreshToken {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return NewRefreshToken(raw = raw, hash = TokenHash.sha256Hex(raw))
    }

    private companion object {
        val secureRandom = SecureRandom()
        const val ISS_CLAIM = "iss"
        const val CLAIM_ROLE = "role"
        const val CLAIM_SUB_ROLE = "subRole"
        const val CLAIM_SESSION = "sessionId"
        const val CLAIM_DEVICE = "deviceId"
    }
}
