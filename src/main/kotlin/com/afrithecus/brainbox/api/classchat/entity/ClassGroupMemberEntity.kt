package com.afrithecus.brainbox.api.classchat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A member of a class chat group. */
@Entity
@Table(name = "class_group_members")
class ClassGroupMemberEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "group_id", nullable = false)
    var groupId: UUID = UUID.randomUUID()

    @Column(name = "member_id", nullable = false)
    var memberId: UUID = UUID.randomUUID()

    @Column(name = "member_name", nullable = false, length = 160)
    var memberName: String = ""

    @Column(name = "member_role", nullable = false, length = 16)
    var memberRole: String = "STUDENT"

    @Column(name = "muted_until")
    var mutedUntil: Instant? = null

    @Column(name = "joined_at", nullable = false, updatable = false)
    var joinedAt: Instant = Instant.now()
}
