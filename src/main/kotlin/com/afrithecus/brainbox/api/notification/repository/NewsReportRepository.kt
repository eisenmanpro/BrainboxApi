package com.afrithecus.brainbox.api.notification.repository

import com.afrithecus.brainbox.api.notification.entity.NewsReportEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NewsReportRepository : JpaRepository<NewsReportEntity, UUID>
