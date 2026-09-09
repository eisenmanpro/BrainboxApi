package com.afrithecus.brainbox.api.common.error

import org.springframework.http.HttpStatus

/**
 * Machine-readable error codes paired with the HTTP status they map to.
 * The serialized "error" value is the enum name (e.g. INVALID_ARGUMENT).
 */
enum class ApiErrorCode(val httpStatus: HttpStatus) {
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
}
