package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * School-admin access rule shared by the ICT-admin surfaces: a platform ADMIN
 * may act on any school; a TEACHER/ICT_ADMIN only on their own school. The actor
 * always comes from the token, never the body.
 */
@Component
class AdminSchoolAccess(private val userRepository: UserRepository) {

    fun require(current: CurrentUser, schoolId: UUID): UserEntity {
        val actor = userRepository.findById(current.userId).orElse(null) ?: throw notFound("User not found")
        if (actor.role == Role.ADMIN) return actor
        if (actor.role == Role.TEACHER && actor.subRole == SubRole.ICT_ADMIN && actor.schoolId == schoolId) return actor
        throw ApiException(ApiErrorCode.FORBIDDEN, "ICT admin access required for this school")
    }
}
