package com.afrithecus.brainbox.api.push

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.push.entity.DeviceTokenEntity
import com.afrithecus.brainbox.api.push.repository.DeviceTokenRepository
import com.afrithecus.brainbox.api.push.web.DeviceRegistrationRequest
import com.afrithecus.brainbox.api.push.web.DeviceUnregisterRequest
import com.afrithecus.brainbox.api.push.web.PushAckPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Device-token lifecycle for FCM (docs/ongoing/api_push_changes.md, LC-1). */
@Service
class DeviceTokenService(private val repository: DeviceTokenRepository) : DeviceTokenStore {

    @Transactional
    fun register(userId: UUID, request: DeviceRegistrationRequest): PushAckPayload {
        val token = request.token.trim()
        if (token.isEmpty()) throw invalidArgument("token is required")
        if (token.length > MAX_TOKEN) throw invalidArgument("token is too long")
        val platform = request.platform.trim().uppercase().ifEmpty { DEFAULT_PLATFORM }
        if (platform !in PLATFORMS) throw invalidArgument("Unknown platform: " + request.platform)
        val entity = repository.findByToken(token) ?: DeviceTokenEntity().apply { this.token = token }
        // A shared device's token moves to the latest account.
        entity.userId = userId
        entity.platform = platform
        entity.appVersion = request.appVersion?.trim()?.takeIf { it.isNotEmpty() }?.take(32)
        repository.saveAndFlush(entity)
        return PushAckPayload(message = "Device registered")
    }

    @Transactional
    fun unregister(userId: UUID, request: DeviceUnregisterRequest): PushAckPayload {
        val token = request.token.trim()
        if (token.isNotEmpty()) repository.deleteByUserIdAndToken(userId, token)
        return PushAckPayload(message = "Device released")
    }

    @Transactional(readOnly = true)
    override fun tokensFor(userId: UUID): List<String> = repository.findAllByUserId(userId).map { it.token }

    @Transactional
    override fun deleteTokens(userId: UUID, tokens: Collection<String>) {
        tokens.forEach { repository.deleteByUserIdAndToken(userId, it) }
    }

    private companion object {
        const val MAX_TOKEN = 512
        const val DEFAULT_PLATFORM = "ANDROID"
        val PLATFORMS = setOf("ANDROID", "IOS", "WEB")
    }
}
