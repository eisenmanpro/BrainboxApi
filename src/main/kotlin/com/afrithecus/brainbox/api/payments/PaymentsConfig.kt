package com.afrithecus.brainbox.api.payments

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/** Selects the live IntaSend gateway or the disabled one from configuration. */
@Configuration
class PaymentsConfig {

    @Bean
    fun paymentGateway(properties: AppPaymentProperties, mapper: ObjectMapper): PaymentGateway =
        if (properties.enabled) IntaSendPaymentGateway(properties, mapper) else DisabledPaymentGateway()
}
