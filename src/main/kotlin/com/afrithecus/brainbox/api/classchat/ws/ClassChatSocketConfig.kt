package com.afrithecus.brainbox.api.classchat.ws

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * Registers the class-chat socket for the teacher, student and parent scopes
 * (the client builds SIGNALING_BASE_URL + "/ws/{scope}/class-chat/{groupId}").
 */
@Configuration
class ClassChatSocketConfig(
    private val handler: ClassChatSocketHandler,
    private val interceptor: ClassChatHandshakeInterceptor,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(
            handler,
            "/ws/teacher/class-chat/*",
            "/ws/student/class-chat/*",
            "/ws/parent/class-chat/*",
        )
            .addInterceptors(interceptor)
            .setAllowedOriginPatterns("*")
    }
}
