package com.afrithecus.brainbox.api.common.error

/**
 * Standard error response body.
 * Contract: docs/backend_contracts/11_SECURITY_PIPELINE_AND_INTEGRATIONS.md §8.1
 * {
 *   "error": "ERROR_CODE",
 *   "message": "Human-readable message",
 *   "details": {},
 *   "timestamp": "long",
 *   "path": "string"
 * }
 */
data class ApiError(
    val error: String,
    val message: String,
    val details: Map<String, Any?>? = null,
    val timestamp: Long,
    val path: String,
)
