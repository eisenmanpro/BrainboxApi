package com.afrithecus.brainbox.api.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Stateless JWT security: /auth and actuator health are public; everything else
 * requires a valid bearer token. 401/403 responses use the standard envelope.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val authTokenFilter: AuthTokenFilter,
    private val envelopeWriter: SecurityEnvelopeWriter,
    private val rateLimitFilter: RateLimitFilter,
    private val idempotencyFilter: com.afrithecus.brainbox.api.common.idempotency.IdempotencyFilter,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val entryPoint = RestAuthenticationEntryPoint(envelopeWriter)
        val accessDeniedHandler = RestAccessDeniedHandler(envelopeWriter)
        return http
            .csrf { it.disable() }
            .headers { headers ->
                headers.frameOptions { it.deny() }
                headers.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                headers.addHeaderWriter { _, response ->
                    response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
                    response.setHeader("Cache-Control", "no-store")
                }
                headers.httpStrictTransportSecurity {
                    it.includeSubDomains(true).maxAgeInSeconds(31536000)
                }
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/auth/login",
                        "/auth/login/phone",
                        "/auth/signup",
                        "/auth/signup/teacher",
                        "/auth/validate-ctc",
                        "/auth/refresh",
                        // Public CBC project browse flow (landing page + guest id).
                        "/cbc/public/**",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/error",
                        // Signed report downloads: the client fetches fileUrl with no
                        // bearer header, so the HMAC-bound token is the authorization.
                        "/teacher/reports/download/**",
                        // WebSocket handshakes authenticate themselves in the
                        // LiveSignalingHandshakeInterceptor (Bearer token).
                        "/ws/**",
                    ).permitAll()
                    // Public reads: school directory, news feed/article, hosted media.
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/schools/**",
                        "/news/**",
                        "/landing/**",
                        "/media/**",
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint(entryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(idempotencyFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
