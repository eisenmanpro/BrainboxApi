package com.afrithecus.brainbox.api.leaderboard.entity

import com.afrithecus.brainbox.api.leaderboard.model.LeaderboardTimeframe
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * The start of the current season for one leaderboard timeframe. Absent means the
 * timeframe has never been reset, so its full window counts.
 */
@Entity
@Table(name = "leaderboard_seasons")
class LeaderboardSeasonEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var timeframe: LeaderboardTimeframe = LeaderboardTimeframe.ALL

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.EPOCH

    constructor()

    constructor(timeframe: LeaderboardTimeframe) {
        this.timeframe = timeframe
    }
}
