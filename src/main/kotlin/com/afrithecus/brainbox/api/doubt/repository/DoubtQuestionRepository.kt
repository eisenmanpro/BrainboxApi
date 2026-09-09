package com.afrithecus.brainbox.api.doubt.repository

import com.afrithecus.brainbox.api.doubt.entity.DoubtQuestionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DoubtQuestionRepository : JpaRepository<DoubtQuestionEntity, UUID>
