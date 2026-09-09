package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<UserEntity, UUID> {

    fun findByPhoneNumber(phoneNumber: String): UserEntity?

    fun findByEmail(email: String): UserEntity?

    fun findByStudentAdmissionNumber(admissionNumber: String): UserEntity?

    fun existsByPhoneNumber(phoneNumber: String): Boolean

    fun existsByEmail(email: String): Boolean

    /** Linked children for a parent (doc 01 §6). */
    fun findByParentUserId(parentUserId: UUID): List<UserEntity>
}
