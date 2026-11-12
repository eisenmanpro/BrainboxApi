package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Provider wire contracts (docs/phone_verification.md): Africa's Talking form POST and Beem JSON
 * POST. A real HTTP server on an ephemeral port captures exactly what would leave the API; no
 * network is used.
 */
class PhoneVerificationProviderTests {

    private val mapper = ObjectMapper()

    private fun user(phone: String) = UserEntity().apply {
        id = UUID.randomUUID()
        phoneNumber = phone
        name = "Test User"
    }

    private fun server(path: String, status: Int, body: String, onRequest: (String, Map<String, String>) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            val requestBody = String(exchange.requestBody.readAllBytes(), UTF_8)
            val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.firstOrNull().orEmpty() }
            onRequest(requestBody, headers)
            val bytes = body.toByteArray(UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun form(body: String): Map<String, String> = body.split("&").filter { it.isNotEmpty() }.associate { part ->
        val index = part.indexOf('=')
        URLDecoder.decode(part.substring(0, index), UTF_8) to URLDecoder.decode(part.substring(index + 1), UTF_8)
    }

    private fun atProperties(baseUrl: String): PhoneVerificationProperties = PhoneVerificationProperties(
        enabled = true,
        provider = "africastalking",
        africastalking = PhoneVerificationProperties.AfricasTalking(
            username = "brainbox",
            apiKey = "secret-key",
            senderId = "BRAINBOX",
            baseUrl = baseUrl,
        ),
    )

    @Test
    fun `africas talking posts the code to the account phone`() {
        val captured = AtomicReference<String>()
        val apiKey = AtomicReference<String>()
        val http = server(
            "/version1/messaging",
            200,
            """{"SMSMessageData":{"Message":"Sent to 1/1","Recipients":[{"status":"Success","statusCode":101}]}}""",
        ) { body, headers ->
            captured.set(body)
            apiKey.set(headers["apikey"].orEmpty())
        }
        try {
            val baseUrl = "http://127.0.0.1:" + http.address.port + "/version1/messaging"
            AfricasTalkingSmsNotifier(atProperties(baseUrl), mapper).send(user("0712345678"), "123456")
            val fields = form(captured.get())
            assertEquals("brainbox", fields["username"])
            assertEquals("+254712345678", fields["to"])
            assertEquals("BRAINBOX", fields["from"])
            assertTrue(fields["message"]!!.contains("123456"))
            assertTrue(fields["message"]!!.contains("10 minutes"))
            assertEquals("secret-key", apiKey.get())
        } finally {
            http.stop(0)
        }
    }

    @Test
    fun `africas talking treats a non-success recipient and a server error as delivery failures`() {
        val failed = server("/version1/messaging", 200, """{"SMSMessageData":{"Recipients":[{"status":"Failed"}]}}""") { _, _ -> }
        try {
            val baseUrl = "http://127.0.0.1:" + failed.address.port + "/version1/messaging"
            assertThrows<IllegalStateException> {
                AfricasTalkingSmsNotifier(atProperties(baseUrl), mapper).send(user("0712345678"), "123456")
            }
        } finally {
            failed.stop(0)
        }
        val broken = server("/version1/messaging", 500, "boom") { _, _ -> }
        try {
            val baseUrl = "http://127.0.0.1:" + broken.address.port + "/version1/messaging"
            assertThrows<IllegalStateException> {
                AfricasTalkingSmsNotifier(atProperties(baseUrl), mapper).send(user("0712345678"), "123456")
            }
        } finally {
            broken.stop(0)
        }
    }

    private fun beemProperties(baseUrl: String): PhoneVerificationProperties = PhoneVerificationProperties(
        enabled = true,
        provider = "beem",
        beem = PhoneVerificationProperties.Beem(
            apiKey = "beem-key",
            secret = "beem-secret",
            senderId = "BRAINBOX",
            baseUrl = baseUrl,
        ),
    )

    @Test
    fun `beem posts the code in the international digits form`() {
        val captured = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val server = server("/v1/send", 200, """{"successful":true,"request_id":42}""") { body, headers ->
            captured.set(body)
            authorization.set(headers["authorization"].orEmpty())
        }
        try {
            val baseUrl = "http://127.0.0.1:" + server.address.port + "/v1/send"
            BeemSmsNotifier(beemProperties(baseUrl), mapper).send(user("0712345678"), "654321")
            val node = mapper.readTree(captured.get())
            assertEquals("BRAINBOX", node.path("source_addr").asString())
            val recipient = node.path("recipients").path(0)
            assertEquals("254712345678", recipient.path("dest_addr").asString())
            assertTrue(node.path("message").asString().contains("654321"))
            val expected = "Basic " + Base64.getEncoder().encodeToString("beem-key:beem-secret".toByteArray(UTF_8))
            assertEquals(expected, authorization.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `beem treats a rejected message and a server error as delivery failures`() {
        val rejected = server("/v1/send", 200, """{"successful":false}""") { _, _ -> }
        try {
            val baseUrl = "http://127.0.0.1:" + rejected.address.port + "/v1/send"
            assertThrows<IllegalStateException> {
                BeemSmsNotifier(beemProperties(baseUrl), mapper).send(user("0712345678"), "654321")
            }
        } finally {
            rejected.stop(0)
        }
        val broken = server("/v1/send", HttpStatus.BAD_GATEWAY.value(), "down") { _, _ -> }
        try {
            val baseUrl = "http://127.0.0.1:" + broken.address.port + "/v1/send"
            assertThrows<IllegalStateException> {
                BeemSmsNotifier(beemProperties(baseUrl), mapper).send(user("0712345678"), "654321")
            }
        } finally {
            broken.stop(0)
        }
    }

    private fun hostpinnacleProperties(baseUrl: String): PhoneVerificationProperties = PhoneVerificationProperties(
        enabled = true,
        provider = "hostpinnacle",
        hostpinnacle = PhoneVerificationProperties.Hostpinnacle(
            apiKey = "portal-key",
            username = "portal-user",
            password = "portal-pass",
            senderId = "BRAINBOX",
            baseUrl = baseUrl,
        ),
    )

    @Test
    fun `hostpinnacle posts the code as a form with the portal credentials and the 254 number`() {
        val captured = AtomicReference<String>()
        val apiKey = AtomicReference<String>()
        val cacheControl = AtomicReference<String>()
        val server = server(
            "/SMSApi/send",
            200,
            """{"status":"success"}""",
        ) { body, headers ->
            captured.set(body)
            apiKey.set(headers["apikey"].orEmpty())
            cacheControl.set(headers["cache-control"].orEmpty())
        }
        try {
            val baseUrl = "http://127.0.0.1:" + server.address.port + "/SMSApi"
            HostpinnacleSmsNotifier(hostpinnacleProperties(baseUrl), mapper).send(user("0712345678"), "123456")
            val fields = form(captured.get())
            assertEquals("portal-user", fields["userid"])
            assertEquals("portal-pass", fields["password"])
            assertEquals("BRAINBOX", fields["senderid"])
            assertEquals("quick", fields["sendMethod"])
            assertEquals("text", fields["msgType"])
            assertEquals("true", fields["duplicatecheck"])
            assertEquals("json", fields["output"])
            assertEquals("254712345678", fields["mobile"])
            assertTrue(fields["msg"]!!.contains("123456"))
            assertTrue(fields["msg"]!!.contains("10 minutes"))
            assertEquals("portal-key", apiKey.get())
            assertEquals("no-cache", cacheControl.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `hostpinnacle classifies the body status rather than the HTTP code because 200 error bodies are failures`() {
        val rejected = server(
            "/SMSApi/send",
            200,
            """{"status":"error","statusCode":"162","reason":"Empty senderId"}""",
        ) { _, _ -> }
        try {
            val baseUrl = "http://127.0.0.1:" + rejected.address.port + "/SMSApi"
            val error = assertThrows<IllegalStateException> {
                HostpinnacleSmsNotifier(hostpinnacleProperties(baseUrl), mapper).send(user("0712345678"), "123456")
            }
            assertTrue(error.message!!.contains("Empty senderId"))
            assertTrue(error.message!!.contains("162"))
        } finally {
            rejected.stop(0)
        }
        val unrecognised = server("/SMSApi/send", 200, """{"message":"queued"}""") { _, _ -> }
        try {
            val baseUrl = "http://127.0.0.1:" + unrecognised.address.port + "/SMSApi"
            assertThrows<IllegalStateException> {
                HostpinnacleSmsNotifier(hostpinnacleProperties(baseUrl), mapper).send(user("0712345678"), "123456")
            }
        } finally {
            unrecognised.stop(0)
        }
        val broken = server("/SMSApi/send", 500, "down") { _, _ -> }
        try {
            val baseUrl = "http://127.0.0.1:" + broken.address.port + "/SMSApi"
            assertThrows<IllegalStateException> {
                HostpinnacleSmsNotifier(hostpinnacleProperties(baseUrl), mapper).send(user("0712345678"), "123456")
            }
        } finally {
            broken.stop(0)
        }
    }

    @Test
    fun `hostpinnacle refuses to send without an approved sender id`() {
        val properties = PhoneVerificationProperties(
            enabled = true,
            provider = "hostpinnacle",
            hostpinnacle = PhoneVerificationProperties.Hostpinnacle(
                apiKey = "portal-key",
                username = "portal-user",
                password = "portal-pass",
                senderId = "",
                baseUrl = "http://127.0.0.1:1/SMSApi",
            ),
        )
        val error = assertThrows<IllegalStateException> {
            HostpinnacleSmsNotifier(properties, mapper).send(user("0712345678"), "123456")
        }
        assertTrue(error.message!!.contains("sender id"))
    }
}
