package com.afrithecus.brainbox.api.push.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A device's FCM registration token (docs/ongoing/api_push_changes.md, LC-1). */
@Entity
@Table(name = "device_tokens")
class DeviceTokenEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    /** Unique: a token may move accounts, in which case the row is reassigned. */
    @Column(nullable = false, length = 512)
    var token: String = ""

    @Column(nullable = false, length = 32)
    var platform: String = "ANDROID"

    @Column(name = "app_version", length = 32)
    var appVersion: String? = null
}
