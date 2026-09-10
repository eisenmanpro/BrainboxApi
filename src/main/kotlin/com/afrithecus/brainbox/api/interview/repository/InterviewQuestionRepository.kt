package com.afrithecus.brainbox.api.interview.repository

import com.afrithecus.brainbox.api.interview.entity.InterviewQuestionEntity
import com.afrithecus.brainbox.api.interview.model.PracticeType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface InterviewQuestionRepository : JpaRepository<InterviewQuestionEntity, UUID> {

    fun findAllByTypeOrderByOrderIndexAsc(type: PracticeType): List<InterviewQuestionEntity>
}
