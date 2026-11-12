package com.afrithecus.brainbox.api.attendance.web

import com.afrithecus.brainbox.api.attendance.AttendanceSmsDlrService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * P4.5: provider delivery-report endpoint. It is public in the security chain because the
 * provider cannot hold a user token; [AttendanceSmsDlrService] authenticates the shared secret
 * with a constant-time comparison and returns 404 while the feature is unconfigured.
 */
@RestController
class AttendanceSmsDlrController(
    private val service: AttendanceSmsDlrService,
) {

    @PostMapping("/webhooks/sms/dlr")
    fun receive(
        @RequestHeader(name = "X-SMS-DLR-Secret", required = false) secret: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        // Read the raw body so JSON and form-encoded providers both reach the parser.
        val body = request.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        return when (service.handle(secret, body)) {
            AttendanceSmsDlrService.Outcome.ACCEPTED,
            AttendanceSmsDlrService.Outcome.UNKNOWN_MESSAGE,
            -> ResponseEntity.ok().build()
            AttendanceSmsDlrService.Outcome.UNAUTHORIZED -> ResponseEntity.status(401).build()
            AttendanceSmsDlrService.Outcome.DISABLED -> ResponseEntity.notFound().build()
            AttendanceSmsDlrService.Outcome.BAD_PAYLOAD -> ResponseEntity.badRequest().build()
        }
    }
}
