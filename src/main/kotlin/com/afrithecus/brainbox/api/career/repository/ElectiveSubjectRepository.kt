package com.afrithecus.brainbox.api.career.repository

import com.afrithecus.brainbox.api.career.entity.ElectiveSubjectEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ElectiveSubjectRepository : JpaRepository<ElectiveSubjectEntity, UUID>
