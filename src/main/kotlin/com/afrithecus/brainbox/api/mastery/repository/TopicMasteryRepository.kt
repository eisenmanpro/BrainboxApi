package com.afrithecus.brainbox.api.mastery.repository

import com.afrithecus.brainbox.api.mastery.entity.TopicMasteryEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/** Projection for a subject-scoped mastery leaderboard. */
interface UserMasteryTotal {
    fun getUserId(): UUID
    fun getScore(): Double
}

interface TopicMasteryRepository : JpaRepository<TopicMasteryEntity, UUID> {

    fun findByUserIdAndTopicId(userId: UUID, topicId: String): TopicMasteryEntity?

    fun findAllByUserIdOrderByScoreAsc(userId: UUID): List<TopicMasteryEntity>

    fun findAllByUserIdAndSubjectOrderByScoreAsc(userId: UUID, subject: String): List<TopicMasteryEntity>

    /** Average mastery per student for one subject, optionally scoped to a school/grade. */
    @Query(
        "SELECT m.userId AS userId, AVG(m.score) AS score FROM TopicMasteryEntity m, UserEntity u " +
            "WHERE m.userId = u.id AND LOWER(m.subject) = LOWER(:subject) " +
            "AND (:schoolId IS NULL OR u.schoolId = :schoolId) " +
            "AND (:grade IS NULL OR u.gradeLevel = :grade) " +
            "GROUP BY m.userId ORDER BY AVG(m.score) DESC"
    )
    fun subjectLeaderboard(
        @Param("subject") subject: String,
        @Param("schoolId") schoolId: UUID?,
        @Param("grade") grade: String?,
        pageable: Pageable,
    ): List<UserMasteryTotal>
}
