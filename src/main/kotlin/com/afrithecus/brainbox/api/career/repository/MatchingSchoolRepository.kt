package com.afrithecus.brainbox.api.career.repository

import com.afrithecus.brainbox.api.career.entity.MatchingSchoolEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MatchingSchoolRepository : JpaRepository<MatchingSchoolEntity, UUID>
