package com.afrithecus.brainbox.api.career.repository

import com.afrithecus.brainbox.api.career.entity.MentorEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MentorRepository : JpaRepository<MentorEntity, UUID>
