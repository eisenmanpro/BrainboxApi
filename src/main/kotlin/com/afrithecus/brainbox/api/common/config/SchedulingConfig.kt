package com.afrithecus.brainbox.api.common.config

import com.afrithecus.brainbox.api.common.idempotency.IdempotencyRepository
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock

@Configuration
@EnableScheduling
class SchedulingConfig(
    private val idempotencyRepository: IdempotencyRepository,
    private val clock: Clock,
) {

    /** Purges expired idempotency records hourly. */
    @Scheduled(fixedDelay = 3_600_000)
    fun purgeExpiredIdempotencyRecords() {
        idempotencyRepository.deleteByExpiresAtBefore(clock.instant())
    }
}
