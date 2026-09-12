package com.afrithecus.brainbox.api.classchat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Per-caller chat read marker; drives unread counts for students and parents. */
@Entity
@Table(name = "class_group_reads")
class ClassGroupReadEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "group_id", nullable = false)
    var groupId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "last_read_at", nullable = false)
    var lastReadAt: Instant = Instant.now()
}
