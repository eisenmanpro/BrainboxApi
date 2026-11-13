package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.RecoveredEmailPayload
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.security.AuthThrottle
import org.springframework.stereotype.Service

/**
 * "Which email did I sign up with?" recovery (gaps.md item 4). The caller supplies the phone number
 * or email on the account and only ever receives the masked address, never the full one and never a
 * password. The address is throttled per identifier to blunt enumeration; the platform IP rate limit
 * sits in front of it as well.
 */
@Service
class AccountRecoveryService(
    private val userRepository: UserRepository,
    private val authThrottle: AuthThrottle,
) {

    fun recoverEmail(raw: String): RecoveredEmailPayload {
        val input = raw.trim()
        if (input.isEmpty()) throw invalidArgument("Enter the phone number or email on your account.")
        val phone = PhoneNumbers.normalize(input)
        val email = if (phone == null) Emails.normalize(input) else null
        if (phone == null && email == null) {
            throw invalidArgument("Enter a valid phone number or email address.")
        }
        authThrottle.check(THROTTLE_PREFIX + (phone ?: email))
        val user = if (phone != null) {
            userRepository.findByPhoneNumber(phone)
        } else {
            userRepository.findByEmailIgnoreCase(email!!)
        }
        if (user == null || !user.isActive) return RecoveredEmailPayload(found = false)
        val contact = user.email?.takeIf { it.isNotBlank() }
            ?: return RecoveredEmailPayload(found = true)
        return RecoveredEmailPayload(found = true, maskedEmail = Emails.mask(contact))
    }

    private companion object {
        const val THROTTLE_PREFIX = "recover-email:"
    }
}
