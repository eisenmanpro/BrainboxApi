package com.afrithecus.brainbox.api.common.error

/**
 * Domain exception carrying a stable error code, human message and optional
 * structured details. Handled centrally by [GlobalExceptionHandler].
 */
class ApiException(
    val code: ApiErrorCode,
    override val message: String,
    val details: Map<String, Any?>? = null,
) : RuntimeException(message)

fun invalidArgument(message: String, details: Map<String, Any?>? = null) =
    ApiException(ApiErrorCode.INVALID_ARGUMENT, message, details)

fun notFound(message: String) = ApiException(ApiErrorCode.NOT_FOUND, message)

fun conflict(message: String, details: Map<String, Any?>? = null) =
    ApiException(ApiErrorCode.CONFLICT, message, details)
