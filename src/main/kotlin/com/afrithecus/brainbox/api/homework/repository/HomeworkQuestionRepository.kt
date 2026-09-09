package com.afrithecus.brainbox.api.homework.repository

import com.afrithecus.brainbox.api.homework.entity.HomeworkQuestionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HomeworkQuestionRepository : JpaRepository<HomeworkQuestionEntity, UUID> {

    fun findAllByHomeworkIdOrderByOrderIndexAsc(homeworkId: String): List<HomeworkQuestionEntity>

    fun deleteByHomeworkId(homeworkId: String)
}
