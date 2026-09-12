package com.afrithecus.brainbox.api.learning.entity

import com.afrithecus.brainbox.api.learning.model.ContentType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** One content block of a post (doc 03 content model). */
@Entity
@Table(name = "learning_content")
class LearningContentEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "post_id", nullable = false)
    var postId: UUID = UUID.randomUUID()

    @Enumerated(EnumType.STRING)
    @Column(name = "c_type", nullable = false, length = 16)
    var contentType: ContentType = ContentType.NOTES

    /** The client-facing material type when it exceeds the canonical enum. */
    @Column(name = "content_type_label", length = 16)
    var contentTypeLabel: String? = null

    @Column(length = 255)
    var title: String? = null

    @Column(columnDefinition = "text")
    var content: String? = null

    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 0

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0

    @Column(name = "thumbnail_url", length = 512)
    var thumbnailUrl: String? = null

    /** QUIZ blocks keep their keyed question JSON here (server-side only). */
    @Column(columnDefinition = "text")
    var metadata: String? = null
}
