package com.afrithecus.brainbox.api.conference.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * A message in a conference slot's meeting discussion (audit 3).
 *
 * Unlike the local-only audit log, discussion messages are server-persisted so
 * they survive refresh and are shared by every session that owns the slot.
 * Writes carry a client id that is unique inside the slot, so an offline replay
 * upserts instead of duplicating.
 */
@Entity
@Table(name = "conference_discussions")
class ConferenceDiscussionEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "slot_id", nullable = false)
    var slotId: UUID = UUID.randomUUID()

    @Column(name = "author_id", nullable = false)
    var authorId: UUID = UUID.randomUUID()

    @Column(name = "author_name", length = 160)
    var authorName: String? = null

    @Column(name = "author_role", nullable = false, length = 32)
    var authorRole: String = "TEACHER"

    @Column(nullable = false, columnDefinition = "text")
    var body: String = ""
}
