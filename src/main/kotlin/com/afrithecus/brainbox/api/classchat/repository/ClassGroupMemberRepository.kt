package com.afrithecus.brainbox.api.classchat.repository

import com.afrithecus.brainbox.api.classchat.entity.ClassGroupMemberEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ClassGroupMemberRepository : JpaRepository<ClassGroupMemberEntity, UUID> {

    fun findAllByGroupIdOrderByMemberNameAsc(groupId: UUID): List<ClassGroupMemberEntity>

    fun findByGroupIdAndMemberId(groupId: UUID, memberId: UUID): ClassGroupMemberEntity?

    fun findAllByMemberId(memberId: UUID): List<ClassGroupMemberEntity>

    fun countByGroupId(groupId: UUID): Long

    /** Bulk delete executes immediately so a member re-sync cannot hit the unique key. */
    @Modifying
    @Query("DELETE FROM ClassGroupMemberEntity m WHERE m.groupId = :groupId")
    fun deleteAllByGroupId(@Param("groupId") groupId: UUID)
}
