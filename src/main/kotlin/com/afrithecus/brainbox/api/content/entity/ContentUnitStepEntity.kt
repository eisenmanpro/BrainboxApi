package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** One ordered step of a content unit. */
@Entity
@Table(name = "content_unit_steps")
class ContentUnitStepEntity : BaseEntity() {

    @Column(name = "unit_id", nullable = false)
    var unitId: UUID = UUID.randomUUID()

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0

    @Column(length = 200)
    var title: String? = null

    @Column(columnDefinition = "text")
    var body: String? = null

    @Column(name = "figure_svg", columnDefinition = "text")
    var figureSvg: String? = null

    @Column(name = "figure_url", length = 512)
    var figureUrl: String? = null
}
