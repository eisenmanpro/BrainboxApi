package com.afrithecus.brainbox.api.common.error

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Translates exceptions into the contract's standard error envelope
 * (doc 11 §8.1/§8.2). Unknown failures never leak internals to the client.
 */
@RestControllerAdvice
class GlobalExceptionHandler(private val meterRegistry: MeterRegistry) {

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException, request: HttpServletRequest): ResponseEntity<ApiError> =
        respond(ex.code, ex.code.httpStatus, ex.message, ex.details, request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ApiError> {
        val details = ex.bindingResult.fieldErrors
            .associate { it.field to (it.defaultMessage ?: "invalid") }
        return respond(ApiErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST, "Validation failed", details, request)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraint(ex: ConstraintViolationException, request: HttpServletRequest): ResponseEntity<ApiError> {
        val details = ex.constraintViolations.associate { it.propertyPath.toString() to it.message }
        return respond(ApiErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST, "Validation failed", details, request)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<ApiError> =
        respond(ApiErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST, "Malformed request body", null, request)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException, request: HttpServletRequest): ResponseEntity<ApiError> =
        respond(ApiErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST, "Invalid argument: " + ex.name, null, request)

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(ex: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<ApiError> =
        respond(ApiErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "Resource not found", null, request)

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException, request: HttpServletRequest): ResponseEntity<ApiError> =
        respond(ApiErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "Access denied", null, request)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(ex: DataIntegrityViolationException, request: HttpServletRequest): ResponseEntity<ApiError> =
        respond(ApiErrorCode.CONFLICT, HttpStatus.CONFLICT, "Operation conflicts with existing data", null, request)

    /**
     * JPA optimistic locking (`@Version`) rejected a write based on a stale row.
     * The client should reload and retry, so this is a 409 rather than a 500.
     */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLock(ex: OptimisticLockingFailureException, request: HttpServletRequest): ResponseEntity<ApiError> {
        meterRegistry.counter("brainbox.conflicts", "type", "optimistic").increment()
        return respond(
            ApiErrorCode.CONFLICT,
            HttpStatus.CONFLICT,
            "The record changed while you were editing it; reload and retry",
            null,
            request,
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        log.error("Unhandled exception on {}", request.requestURI, ex)
        return respond(ApiErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong", null, request)
    }

    private fun respond(
        code: ApiErrorCode,
        status: HttpStatus,
        message: String,
        details: Map<String, Any?>?,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val builder = ResponseEntity.status(status)
        // Throttling advertises a Retry-After so the client can back off.
        if (code == ApiErrorCode.TOO_MANY_REQUESTS) {
            (details?.get("retryAfter") as? Number)?.toLong()?.let { builder.header("Retry-After", it.toString()) }
        }
        return builder.body(
            ApiError(
                error = code.name,
                message = message,
                details = details,
                timestamp = System.currentTimeMillis(),
                path = request.requestURI,
            )
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
