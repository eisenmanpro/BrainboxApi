package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * Allocates unique, server-issued Class Teacher Codes. Codes are never generated
 * client-side (docs/ongoing/api_teacher_roster_changes.md §Teacher auth).
 */
@Component
class TeacherCodeGenerator(private val teacherCodeRepository: TeacherCodeRepository) {

    fun generate(): String {
        repeat(MAX_ATTEMPTS) {
            val candidate = buildString(CODE_LENGTH) {
                repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            }
            if (!teacherCodeRepository.existsByCode(candidate)) return candidate
        }
        throw ApiException(ApiErrorCode.CONFLICT, "Could not allocate a teacher code, retry")
    }

    private companion object {
        const val CODE_LENGTH = 6
        const val MAX_ATTEMPTS = 100
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
    }
}
