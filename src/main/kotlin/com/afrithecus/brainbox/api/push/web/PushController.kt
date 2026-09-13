package com.afrithecus.brainbox.api.push.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.push.DeviceTokenService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** FCM device registration (docs/ongoing/api_push_changes.md, LC-1). */
@RestController
@RequestMapping("/push")
class PushController(private val service: DeviceTokenService) {

    @PostMapping("/device")
    fun register(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestBody request: DeviceRegistrationRequest,
    ): PushAckPayload = service.register(current.userId, request)

    @PostMapping("/device/unregister")
    fun unregister(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestBody request: DeviceUnregisterRequest,
    ): PushAckPayload = service.unregister(current.userId, request)
}
