package com.afrithecus.brainbox.api.media

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * ClamAV scanning over clamd's TCP INSTREAM command, so the object never has to be
 * written to a temporary file on the API node.
 *
 * The wire protocol is fixed: `zINSTREAM\0`, then the bytes as
 * (4-byte big-endian length, data) chunks, then a zero-length chunk. clamd answers one
 * line — `stream: OK`, `stream: <Signature> FOUND`, or an error text. Anything that is
 * not a clean reply is reported as [MediaScanStatus.ERROR] (never as clean), and the
 * caller decides whether that refuses the upload.
 *
 * `z` selects the NUL-terminated command form; the stream is bounded by
 * `app.media.scan.timeout-millis` so an unresponsive clamd cannot hold a request open.
 */
@Component
class ClamAvMediaScanner(
    private val properties: MediaProperties,
) : MediaScanner {

    override val name: String = "clamav"

    private val log = LoggerFactory.getLogger(javaClass)

    override fun scan(bytes: ByteArray, contentType: String?): MediaScanResult {
        val settings = properties.scan.clamav
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(settings.host, settings.port), properties.scan.timeoutMillis.toInt())
                socket.soTimeout = properties.scan.timeoutMillis.toInt()
                val out = DataOutputStream(socket.getOutputStream())
                out.write("zINSTREAM\u0000".toByteArray(StandardCharsets.US_ASCII))
                var offset = 0
                while (offset < bytes.size) {
                    val length = minOf(CHUNK_BYTES, bytes.size - offset)
                    out.writeInt(length)
                    out.write(bytes, offset, length)
                    offset += length
                }
                out.writeInt(0)
                out.flush()
                // clamd answers one short line; read it with a bounded reader so a
                // misbehaving service cannot stream an unbounded reply.
                val reply = readBoundedLine(DataInputStream(socket.getInputStream())).trim()
                verdict(reply)
            }
        } catch (failure: Exception) {
            log.warn("clamd scan failed: {}", failure.message)
            MediaScanResult(MediaScanStatus.ERROR, "The malware scanner is unavailable")
        }
    }

    /** Reads one \n-terminated line, capped at [MAX_REPLY_CHARS] characters. */
    private fun readBoundedLine(input: DataInputStream): String {
        val builder = StringBuilder()
        while (builder.length < MAX_REPLY_CHARS) {
            val next = input.read()
            if (next < 0 || next == '\n'.code) break
            builder.append(next.toChar())
        }
        return builder.toString()
    }

    private fun verdict(reply: String): MediaScanResult = when {
        reply.endsWith("OK") -> MediaScanResult(MediaScanStatus.CLEAN)
        reply.endsWith("FOUND") -> MediaScanResult(
            MediaScanStatus.INFECTED,
            reply.removePrefix("stream:").removeSuffix("FOUND").trim().ifEmpty { "malware signature match" },
        )
        reply.isBlank() -> MediaScanResult(MediaScanStatus.ERROR, "The malware scanner returned no verdict")
        else -> MediaScanResult(MediaScanStatus.ERROR, reply.take(MAX_DETAIL_CHARS))
    }

    private companion object {
        const val CHUNK_BYTES = 32 * 1024
        const val MAX_REPLY_CHARS = 512
        const val MAX_DETAIL_CHARS = 200
    }
}
