package com.afrithecus.brainbox.api.leaderboard.repository

import com.afrithecus.brainbox.api.leaderboard.entity.LeaderboardSeasonEntity
import com.afrithecus.brainbox.api.leaderboard.model.LeaderboardTimeframe
import org.springframework.data.jpa.repository.JpaRepository

interface LeaderboardSeasonRepository : JpaRepository<LeaderboardSeasonEntity, LeaderboardTimeframe>
