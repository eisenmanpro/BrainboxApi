package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ReviewerTrustEntity
import com.afrithecus.brainbox.api.content.repository.ContentReviewRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.content.repository.ReviewerTrustRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.math.abs

/**
 * Phase 7.4a: the human review workflow. One decision per reviewer/version,
 * aggregated quorum outcome (two distinct approvals; any reject resolves), the
 * distinct-human cap that a single weighted expert cannot bypass, content-unit
 * propagation, and the staff exclusion from the reviewer trust ladder.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ModerationReviewTests(
    @Autowired private val reviewService: ReviewService,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val reviews: ContentReviewRepository,
    @Autowired private val outcomes: ModerationOutcomeRepository,
    @Autowired private val trust: ReviewerTrustRepository,
    @Autowired private val users: UserRepository,
    @Autowired private val entityManager: EntityManager,
) {

    @Test
    fun `one approve leaves UNREVIEWED and the second distinct approve resolves REVIEWED`() {
        val unit = seedUnit()
        val first = seedUser(Role.TEACHER, name = "Teacher One")
        val second = seedUser(Role.TEACHER, name = "Teacher Two")

        val afterFirst = reviewService.recordDecision(
            first, CONTENT_TYPE_UNIT, unit.id, 1, "APPROVE", listOf("clear"), "looks good"
        )
        check(afterFirst.state == "UNREVIEWED")
        check(afterFirst.approvals == 1)
        check(afterFirst.rejections == 0)
        check(afterFirst.decidedAt == null)
        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "UNREVIEWED")

        val afterSecond = reviewService.recordDecision(
            second, CONTENT_TYPE_UNIT, unit.id, 1, "APPROVE", emptyList(), null
        )
        check(afterSecond.state == "REVIEWED")
        check(afterSecond.approvals == 2)
        check(afterSecond.weightedApprovals == 2.0)
        check(afterSecond.quorumRequired == 2)
        check(afterSecond.decidedAt != null)

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "REVIEWED")
        check(outcomes.findByContentTypeAndContentIdAndContentVersion(CONTENT_TYPE_UNIT, unit.id, 1)?.state == "REVIEWED")
    }

    @Test
    fun `a reject resolves REJECTED and propagates to the content unit`() {
        val unit = seedUnit()
        val reviewer = seedUser(Role.TEACHER, name = "Teacher Reject")

        val outcome = reviewService.recordDecision(
            reviewer, CONTENT_TYPE_UNIT, unit.id, 1, "REJECT", listOf("wrong_answer"), "answer key is wrong"
        )
        check(outcome.state == "REJECTED")
        check(outcome.rejections == 1)
        check(outcome.approvals == 0)
        check(outcome.decidedAt != null)

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "REJECTED")
    }

    @Test
    fun `re-deciding replaces rather than duplicates the reviewers row`() {
        val unit = seedUnit()
        val reviewer = seedUser(Role.TEACHER, name = "Teacher Redecide")

        reviewService.recordDecision(reviewer, CONTENT_TYPE_UNIT, unit.id, 1, "APPROVE", listOf("clear"), "ok")
        reviewService.recordDecision(
            reviewer, CONTENT_TYPE_UNIT, unit.id, 1, "REQUEST_CHANGES", listOf("too_hard"), "tighten the steps"
        )

        entityManager.flush()
        entityManager.clear()

        val rows = reviews.findAllByContentTypeAndContentIdAndContentVersion(CONTENT_TYPE_UNIT, unit.id, 1)
        check(rows.size == 1)
        check(rows[0].reviewerId == reviewer.id)
        check(rows[0].decision == "REQUEST_CHANGES")
        check(rows[0].comment == "tighten the steps")
        check(rows[0].reasonTags!!.contains("too_hard"))

        val outcome = outcomes.findByContentTypeAndContentIdAndContentVersion(CONTENT_TYPE_UNIT, unit.id, 1)!!
        check(outcome.state == "UNREVIEWED")
        check(outcome.approvals == 0)
        check(outcome.weightedApprovals == 0.0)
    }

    @Test
    fun `a weighted high-tier reviewer alone cannot satisfy the quorum`() {
        val unit = seedUnit()
        val expert = seedUser(Role.TEACHER, name = "Weighted Expert")
        trust.save(
            ReviewerTrustEntity().apply {
                teacherId = expert.id
                tier = 2
                reviewsCount = 50
                agreements = 45
                weight = 2.0
            }
        )

        val outcome = reviewService.recordDecision(
            expert, CONTENT_TYPE_UNIT, unit.id, 1, "APPROVE", emptyList(), null
        )
        check(outcome.approvals == 1)
        check(outcome.weightedApprovals == 2.0)
        check(outcome.state == "UNREVIEWED") // one human, however weighty, is not a quorum

        val stored = reviews.findByReviewerIdAndContentTypeAndContentIdAndContentVersion(
            expert.id, CONTENT_TYPE_UNIT, unit.id, 1
        )
        check(stored != null && abs(stored.weight - 2.0) < 1e-9)

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "UNREVIEWED")
    }

    @Test
    fun `staff reviewers never gain a trust tier or weight`() {
        val unit = seedUnit()
        val admin = seedUser(Role.ADMIN, name = "Platform Admin")
        val ictAdmin = seedUser(Role.TEACHER, name = "ICT Admin", subRole = SubRole.ICT_ADMIN)

        reviewService.recordDecision(admin, CONTENT_TYPE_UNIT, unit.id, 1, "APPROVE", emptyList(), null)
        reviewService.recordDecision(ictAdmin, CONTENT_TYPE_UNIT, unit.id, 2, "APPROVE", emptyList(), null)

        entityManager.flush()
        entityManager.clear()

        check(trust.findByTeacherId(admin.id) == null)
        check(trust.findByTeacherId(ictAdmin.id) == null)

        val adminReview = reviews.findByReviewerIdAndContentTypeAndContentIdAndContentVersion(
            admin.id, CONTENT_TYPE_UNIT, unit.id, 1
        )
        val ictReview = reviews.findByReviewerIdAndContentTypeAndContentIdAndContentVersion(
            ictAdmin.id, CONTENT_TYPE_UNIT, unit.id, 2
        )
        check(adminReview != null && abs(adminReview.weight - 1.0) < 1e-9)
        check(ictReview != null && abs(ictReview.weight - 1.0) < 1e-9)
    }

    // ---------------------------------------------------------------- fixtures

    private fun seedUnit(): ContentUnitEntity =
        contentUnits.save(
            ContentUnitEntity().apply {
                generationKey = "test:moderation:" + UUID.randomUUID()
                taskType = "NOTES"
                subject = "Mathematics"
                gradeLevel = "Grade 4"
                title = "Moderation fixture"
                reviewState = "UNREVIEWED"
            }
        )

    private fun seedUser(role: Role, name: String, subRole: SubRole? = null): UserEntity {
        val suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10)
        return users.save(
            UserEntity().apply {
                phoneNumber = "07" + suffix.substring(0, 8)
                email = "moderation-$suffix@test.brainbox"
                passwordHash = "not-a-real-hash"
                this.name = name
                this.role = role
                this.subRole = subRole
                isActive = true
                isVerified = true
            }
        )
    }

    private companion object {
        const val CONTENT_TYPE_UNIT = "UNIT"
    }
}
