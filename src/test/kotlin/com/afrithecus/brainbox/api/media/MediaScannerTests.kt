package com.afrithecus.brainbox.api.media

import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scanner providers and their wire behaviour, checked against stub servers so the
 * clamd INSTREAM framing and the HTTP sidecar contract are pinned rather than assumed.
 * Nothing here needs a scanner binary or network access.
 *
 * The *policy* around a verdict (fail-open, quarantine vs delete, alerts, the scan log) is
 * covered end-to-end in `MediaSecurityWebTests`, because the gate reads it from runtime
 * settings.
 */
class MediaScannerTests {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // ------------------------------------------------------------------ clamd

    @Test
    fun clamdInstreamFramingAndVerdictsAreParsed() {
        val received = StringBuilder()
        val server = ServerSocket(0)
        val pool = Executors.newSingleThreadExecutor()
        pool.submit {
            // One connection per scan; each handler answers with the queued reply.
            for (reply in listOf("stream: OK\n", "stream: Eicar-Test-Signature FOUND\n")) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val command = StringBuilder()
                    while (true) {
                        val next = input.read()
                        if (next <= 0) break
                        command.append(next.toChar())
                    }
                    received.append(command).append('|')
                    var total = 0
                    while (true) {
                        val length = input.readInt()
                        if (length == 0) break
                        val chunk = ByteArray(length)
                        input.readFully(chunk)
                        total += chunk.size
                    }
                    received.append(total).append('|')
                    DataOutputStream(socket.getOutputStream())
                        .apply { write(reply.toByteArray(StandardCharsets.US_ASCII)); flush() }
                }
            }
        }
        val properties = MediaProperties(
            scan = MediaProperties.Scan(
                provider = "clamav",
                clamav = MediaProperties.Scan.Clamav(host = "127.0.0.1", port = server.localPort),
            ),
        )
        val scanner = ClamAvMediaScanner(properties)

        val clean = scanner.scan(png, "image/png")
        assertEquals(MediaScanStatus.CLEAN, clean.status)
        assertEquals("clamav", scanner.name)

        val infected = scanner.scan(png, "image/png")
        assertEquals(MediaScanStatus.INFECTED, infected.status)
        assertEquals("Eicar-Test-Signature", infected.detail)

        pool.shutdown()
        server.close()
        pool.awaitTermination(5, TimeUnit.SECONDS)
        val frames = received.toString().split('|')
        assertTrue(frames[0].startsWith("zINSTREAM"), "clamd must be addressed with INSTREAM, was " + frames[0])
        assertEquals(png.size.toString(), frames[1], "the whole object must be streamed to the scanner")
    }

    @Test
    fun anUnreachableClamdIsAnErrorNotACleanVerdict() {
        // Port 1 is not listening; the scanner must report ERROR rather than throw.
        val properties = MediaProperties(
            scan = MediaProperties.Scan(
                provider = "clamav",
                timeoutMillis = 500,
                clamav = MediaProperties.Scan.Clamav(host = "127.0.0.1", port = 1),
            ),
        )
        val result = ClamAvMediaScanner(properties).scan(png, "image/png")
        assertEquals(MediaScanStatus.ERROR, result.status)
    }

    // ------------------------------------------------------------------- http

    @Test
    fun theHttpSidecarContractIsHonoured() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
        val bodies = linkedMapOf(
            "/clean" to """{"status":"CLEAN"}""",
            "/infected" to """{"status":"INFECTED","detail":"Win.Test.EICAR"}""",
            "/unknown" to """{"status":"MAYBE"}""",
            "/garbage" to "not json",
        )
        bodies.forEach { (path, body) ->
            server.createContext(path) { exchange ->
                exchange.requestBody.readBytes()
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.createContext("/boom") { exchange -> exchange.sendResponseHeaders(500, -1) }
        server.start()

        fun scannerFor(path: String) = HttpMediaScanner(
            MediaProperties(
                scan = MediaProperties.Scan(
                    provider = "http",
                    http = MediaProperties.Scan.Http(url = "http://127.0.0.1:" + server.address.port + path),
                ),
            ),
            ObjectMapper(),
        )

        try {
            assertEquals(MediaScanStatus.CLEAN, scannerFor("/clean").scan(png, "image/png").status)
            val infected = scannerFor("/infected").scan(png, "image/png")
            assertEquals(MediaScanStatus.INFECTED, infected.status)
            assertEquals("Win.Test.EICAR", infected.detail)
            // An unknown or unreadable verdict is never an approval.
            assertEquals(MediaScanStatus.ERROR, scannerFor("/unknown").scan(png, "image/png").status)
            assertEquals(MediaScanStatus.ERROR, scannerFor("/garbage").scan(png, "image/png").status)
            assertEquals(MediaScanStatus.ERROR, scannerFor("/boom").scan(png, "image/png").status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun theHttpProviderRefusesToGuessWithoutAUrl() {
        val scanner = HttpMediaScanner(
            MediaProperties(scan = MediaProperties.Scan(provider = "http")),
            ObjectMapper(),
        )
        val result = scanner.scan(png, "image/png")
        assertEquals(MediaScanStatus.ERROR, result.status)
        assertTrue(result.detail!!.contains("scanner URL"), result.detail!!)
    }

}
