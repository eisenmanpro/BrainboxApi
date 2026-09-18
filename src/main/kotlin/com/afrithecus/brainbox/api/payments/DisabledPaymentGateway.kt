package com.afrithecus.brainbox.api.payments

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException

/** The gateway used when payments are disabled; every call fails closed. */
class DisabledPaymentGateway : PaymentGateway {

    override val name: String = "DISABLED"

    override fun stkPush(command: StkPushCommand): StkPushOutcome =
        throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "Payments are disabled")

    override fun status(providerReference: String): GatewayPaymentState =
        throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "Payments are disabled")
}
