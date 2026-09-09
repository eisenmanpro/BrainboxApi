package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.auth.web.UserPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import org.springframework.stereotype.Component

/** Maps a user to the contract UserPayload (doc 01 §2.1) with school name join. */
@Component
class UserPayloadFactory(private val schoolRepository: SchoolRepository) {

    fun toPayload(user: UserEntity): UserPayload {
        val schoolName = user.schoolId?.let { id ->
            schoolRepository.findById(id).map { it.name }.orElse(null)
        }
        return UserPayload(
            id = user.id.toString(),
            phoneNumber = user.phoneNumber,
            name = user.name,
            role = user.role.name,
            subRole = user.subRole?.name,
            schoolId = user.schoolId?.toString(),
            schoolName = schoolName,
            studentAdmissionNumber = user.studentAdmissionNumber,
            parentId = user.parentUserId?.toString(),
            childId = null,
            referredByTeacherCode = user.referredByTeacherCode,
            joinedTeacherId = user.joinedTeacherId?.toString(),
            gradeLevel = user.gradeLevel,
            isActive = user.isActive,
            isVerified = user.isVerified,
            createdAt = user.createdAt.toEpochMilli(),
            lastLogin = user.lastLogin?.toEpochMilli(),
        )
    }
}
