package com.afrithecus.brainbox.api.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class AppBeans {

    /** Injectable clock for deterministic token/expiry logic and tests. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
