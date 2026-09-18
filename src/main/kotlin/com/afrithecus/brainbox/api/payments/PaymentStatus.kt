package com.afrithecus.brainbox.api.payments

/** Our payment attempt state machine (doc 07 section 3.4). */
enum class PaymentStatus { PENDING, PROCESSING, SUCCESS, FAILED, CANCELLED }
