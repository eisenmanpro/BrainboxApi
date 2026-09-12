package com.afrithecus.brainbox.api.live.ws

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * Registers the live-class signaling channel. The client builds its URL as
 * SIGNALING_BASE_URL + "/ws/live/{classId}" (the flavor base already ends in
 * /ws) and the contract documents /ws/live/class/{classId}, so all three shapes
 * are mapped to the same handler.
 */
@Configuration
@EnableWebSocket
class LiveSignalingConfig(
    private val handler: LiveSignalingHandler,
    private val interceptor: LiveSignalingHandshakeInterceptor,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/live/*", "/ws/live/class/*", "/ws/ws/live/*")
            .addInterceptors(interceptor)
            .setAllowedOriginPatterns("*")
    }
}
