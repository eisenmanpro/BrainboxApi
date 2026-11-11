package com.afrithecus.brainbox.api.achievements

import com.afrithecus.brainbox.api.achievements.web.ContestHistoryEntryPayload
import com.afrithecus.brainbox.api.achievements.web.LeaderboardResponsePayload
import com.afrithecus.brainbox.api.achievements.web.MasteryTreePayload
import com.afrithecus.brainbox.api.achievements.web.RewardItemPayload
import com.afrithecus.brainbox.api.achievements.web.UserAchievementsPayload
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.contests.entity.ContestEntity
import com.afrithecus.brainbox.api.contests.entity.ContestSubmissionEntity
import com.afrithecus.brainbox.api.contests.model.ContestLifecycle
import com.afrithecus.brainbox.api.contests.repository.ContestRepository
import com.afrithecus.brainbox.api.contests.repository.ContestSubmissionRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.mastery.entity.TopicMasteryEntity
import com.afrithecus.brainbox.api.mastery.repository.TopicMasteryRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Achievements, XP, badges, leaderboards and rewards (doc 03 §7/§8): XP curve,
 * redemption economics, badge unlocking, derived streaks/rank, mastery tree and
 * self-only scoping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AchievementsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val topicMasteryRepository: TopicMasteryRepository,
    @Autowired private val contestRepository: ContestRepository,
    @Autowired private val contestSubmissionRepository: ContestSubmissionRepository,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val examSubmissionRepository: ExamSubmissionRepository,
) {

    private val premiumReward = "77777777-7777-7777-7777-000000000001"
    private val firstBadge = "66666666-6666-6666-6666-000000000001"

    private fun auth(token: String) = "Bearer " + token

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"Ach ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun achievements(student: AuthResponse): UserAchievementsPayload {
        val body = mockMvc.perform(
            get("/achievements/user/${student.user.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, UserAchievementsPayload::class.java)
    }

    @Test
    fun `xp curve and badge unlocking`() {
        val student = signup("0778700001")
        val initial = achievements(student)
        check(initial.level == 1)
        check(initial.totalXP == 0)
        check(initial.badges.size == 10)
        check(initial.badges.none { it.isUnlocked })
        check(initial.masteryLevel == "NOVICE")
        check(initial.scholarshipFlags.isNotEmpty())

        val afterAward = mockMvc.perform(
            post("/achievements/xp/award?userId=${student.user.id}&amount=2500&activityType=quiz")
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val awarded = objectMapper.readValue(afterAward, UserAchievementsPayload::class.java)
        check(awarded.totalXP == 2500)
        check(awarded.level == 3)
        check(awarded.currentXP == 500)
        check(awarded.levelTitle == "Explorer")

        val unlockedBody = mockMvc.perform(
            post("/achievements/badges/unlock?userId=${student.user.id}&badgeId=${firstBadge}")
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val unlocked = objectMapper.readValue(unlockedBody, UserAchievementsPayload::class.java)
        check(unlocked.badges.first { it.isUnlocked }.id == firstBadge)
        // idempotent: unlocking again keeps a single earned badge
        mockMvc.perform(
            post("/achievements/badges/unlock?userId=${student.user.id}&badgeId=${firstBadge}")
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk)
        check(achievements(student).badges.count { it.isUnlocked } == 1)

        mockMvc.perform(
            post("/achievements/xp/award?userId=${student.user.id}&amount=0")
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/achievements/badges/unlock?userId=${student.user.id}&badgeId=${UUID.randomUUID()}")
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `reward redemption checks XP and is one-time`() {
        val student = signup("0778700002")
        val storeBody = mockMvc.perform(
            get("/achievements/rewards").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val store = objectMapper.readValue(storeBody, Array<RewardItemPayload>::class.java)
        check(store.size == 4)
        check(store.none { it.isRedeemed })

        mockMvc.perform(
            post("/achievements/rewards/redeem?userId=${student.user.id}&rewardId=${premiumReward}")
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isConflict)

        mockMvc.perform(
            post("/achievements/xp/award?userId=${student.user.id}&amount=6000&activityType=exam")
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk)

        val redeemedBody = mockMvc.perform(
            post("/achievements/rewards/redeem?userId=${student.user.id}&rewardId=${premiumReward}")
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val redeemed = objectMapper.readValue(redeemedBody, RewardItemPayload::class.java)
        check(redeemed.isRedeemed)
        check(redeemed.couponCode != null && redeemed.couponCode!!.startsWith("BB-"))
        check(achievements(student).totalXP == 1000)

        mockMvc.perform(
            post("/achievements/rewards/redeem?userId=${student.user.id}&rewardId=${premiumReward}")
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isConflict)
        val refreshed = mockMvc.perform(
            get("/achievements/rewards").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(refreshed, Array<RewardItemPayload>::class.java).first { it.id == premiumReward }.isRedeemed)
    }

    @Test
    fun `leaderboards are ranked and mark the current user`() {
        val student = signup("0778700003")
        val other = signup("0778700004")
        mockMvc.perform(
            post("/achievements/xp/award?userId=${student.user.id}&amount=3000&activityType=exam")
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/achievements/xp/award?userId=${other.user.id}&amount=9000&activityType=exam")
                .header("Authorization", auth(other.sessionToken!!))
        ).andExpect(status().isOk)

        val national = objectMapper.readValue(
            mockMvc.perform(get("/achievements/leaderboard?type=national&limit=10").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            LeaderboardResponsePayload::class.java,
        )
        check(national.entries.isNotEmpty())
        check(national.entries.first().rank == 1)
        check(national.entries.first().score >= national.entries.last().score)
        check(national.userEntry?.isCurrentUser == true)

        val weekly = objectMapper.readValue(
            mockMvc.perform(get("/achievements/leaderboard?type=weekly&limit=10").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            LeaderboardResponsePayload::class.java,
        )
        check(weekly.entries.isNotEmpty())
        check(weekly.entries.any { it.isCurrentUser })
        check(weekly.userEntry?.isCurrentUser == true)
        check(weekly.entries.first().score >= weekly.entries.last().score)
    }

    @Test
    fun `contest history and mastery tree derive from real data`() {
        val student = signup("0778700005")
        val studentId = UUID.fromString(student.user.id)
        val contest = contestRepository.save(ContestEntity().apply {
            title = "Ach Contest"
            subject = "Mathematics"
            grade = "Form 3"
            startTime = Instant.now().minusSeconds(7200)
            endTime = Instant.now().minusSeconds(3600)
            entryFee = 0
            prize = "Ksh 5,000"
            lifecycle = ContestLifecycle.PUBLISHED
            createdBy = studentId
        })
        contestSubmissionRepository.save(ContestSubmissionEntity().apply {
            this.contestId = contest.id
            this.userId = studentId
            score = 90
            totalPoints = 100
            percentage = 90
            correctCount = 9
            questionCount = 10
            submittedAt = Instant.now().minusSeconds(3600)
        })
        topicMasteryRepository.save(TopicMasteryEntity().apply {
            this.userId = studentId
            topicId = "Algebra"
            topicName = "Algebra"
            subject = "Mathematics"
            score = 40.0
            previousScore = 30.0
            attemptsCount = 2
            questionsAttempted = 10
            correctAnswers = 4
            lastPracticed = Instant.now()
        })

        val history = objectMapper.readValue(
            mockMvc.perform(get("/achievements/user/${student.user.id}/contest-history").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ContestHistoryEntryPayload>::class.java,
        )
        check(history.size == 1)
        check(history.single().rank == 1)
        check(history.single().prizeWon == "Ksh 5,000")

        val tree = objectMapper.readValue(
            mockMvc.perform(get("/achievements/user/${student.user.id}/mastery-tree").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            MasteryTreePayload::class.java,
        )
        check(tree.rootNodeId == "root")
        check(tree.nodes.size == 2)
        check(tree.recommendedPath.contains("topic-Algebra"))
        check(tree.nodes.first { it.nodeId == "topic-Algebra" }.masteryPercent == 40f)
    }

    @Test
    fun `automatic badge criteria, streak credits, daily series and monthly digest are server-computed`() {
        val student = signup("0778700008")
        val studentId = UUID.fromString(student.user.id)
        val exams = (0 until 5).map { index ->
            examRepository.save(ExamEntity().apply {
                title = "Achievement quiz " + index
                subject = "Mathematics"
                durationMinutes = 20
                questionCount = 20
                totalPoints = 100
                createdBy = studentId
            })
        }
        repeat(5) { index ->
            examSubmissionRepository.save(ExamSubmissionEntity().apply {
                this.examId = exams[index].id
                this.userId = studentId
                score = 85
                totalPoints = 100
                percentage = 85
                correctCount = 17
                questionCount = 20
                timeTakenSeconds = 300
                submittedAt = Instant.now().minusSeconds(index * 60L)
            })
        }
        // XP award is an event boundary: badge rules run there before the payload is built.
        val awardResult = mockMvc.perform(
            post("/achievements/xp/award?userId=" + student.user.id + "&amount=1000&activityType=quiz")
                .header("Authorization", auth(student.sessionToken!!))
        ).andReturn()
        check(awardResult.response.status == 200) { "award failed: " + awardResult.response.status + " " + awardResult.response.contentAsString }

        val initial = achievements(student)
        check(initial.badges.first { it.title == "Quiz Master" }.isUnlocked)
        check(initial.dailyActivity.size == 7)
        check(initial.dailyActivity.last().active)
        check(initial.monthlyDigest != null)
        check(initial.monthlyDigest!!.xpGained >= 1000)
        // The catalogue's seed rows have no provider URLs, so null is the honest value;
        // applicationStatus stays null because this server does not track applications yet.
        check(initial.scholarshipFlags.isNotEmpty())
        check(initial.scholarshipFlags.all { it.applicationStatus == null })

        val buy = objectMapper.readValue(
            mockMvc.perform(
                post("/achievements/streak/freeze?userId=" + student.user.id)
                    .header("Authorization", auth(student.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"action":"BUY","kind":"FREEZE"}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            UserAchievementsPayload::class.java,
        )
        check(buy.streakFreezes == 1)
        check(buy.totalXP == 700)

        val yesterday = java.time.LocalDate.now().minusDays(1).toString()
        val used = objectMapper.readValue(
            mockMvc.perform(
                post("/achievements/streak/freeze?userId=" + student.user.id)
                    .header("Authorization", auth(student.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"USE\",\"kind\":\"FREEZE\",\"date\":\"" + yesterday + "\"}")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            UserAchievementsPayload::class.java,
        )
        check(used.streakFreezes == 0)
        check(used.streakCredits.size == 1)
        check(used.currentStreak >= 1)

        mockMvc.perform(
            post("/achievements/streak/freeze?userId=" + student.user.id)
                .header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"USE\",\"kind\":\"FREEZE\",\"date\":\"" + yesterday + "\"}")
        ).andExpect(status().isConflict)
    }

    @Test
    fun `achievements are self only`() {
        val a = signup("0778700006")
        val b = signup("0778700007")
        mockMvc.perform(get("/achievements/user/${b.user.id}").header("Authorization", auth(a.sessionToken!!)))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            post("/achievements/xp/award?userId=${b.user.id}&amount=100")
                .header("Authorization", auth(a.sessionToken!!))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/achievements/rewards/redeem?userId=${b.user.id}&rewardId=${premiumReward}")
                .header("Authorization", auth(a.sessionToken!!))
        ).andExpect(status().isForbidden)
    }
}
