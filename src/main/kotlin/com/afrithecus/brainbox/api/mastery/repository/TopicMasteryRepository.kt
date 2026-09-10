package com.afrithecus.brainbox.api.mastery.repository

import com.afrithecus.brainbox.api.mastery.entity.TopicMasteryEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TopicMasteryRepository : JpaRepository<TopicMasteryEntity, UUID> {

    fun findByUserIdAndTopicId(userId: UUID, topicId: String): TopicMasteryEntity?

    fun findAllByUserIdOrderByScoreAsc(userId: UUID): List<TopicMasteryEntity>

    fun findAllByUserIdAndSubjectOrderByScoreAsc(userId: UUID, subject: String): List<TopicMasteryEntity>
}
