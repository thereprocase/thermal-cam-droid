package com.thereprocase.thermalfield

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal class NetworkFrames(private val url: String, private val active: AtomicBoolean) {
    @Volatile private var connection: HttpURLConnection? = null
    fun close() { active.set(false); connection?.disconnect() }
    fun frames(receive: (ByteArray, Long) -> Unit) {
        val uri = URI(url)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null) { "Enter an HTTP(S) radiometric bridge address without embedded credentials" }
        val current = uri.toURL().openConnection() as HttpURLConnection
        // Gain changes observed on the bench exceed 2.5 seconds. A stale image
        // is reported by frame age while this bounded wait allows recovery.
        connection = current; current.connectTimeout = 4000; current.readTimeout = 12000
        current.setRequestProperty("Accept", "multipart/x-mixed-replace")
        check(current.responseCode == 200) { "Bridge returned HTTP ${current.responseCode}" }
        check(current.getHeaderField("X-Thermal-Protocol") == "thermal-field-v1" && current.getHeaderField("X-Thermal-Format") == "yuyv-256x384-u16le-k64") { "Unsupported radiometric protocol; ordinary camera JPEGs do not contain the required temperature plane" }
        current.inputStream.use { input ->
            val stream = BufferedInputStream(input, 256 * 1024)
            while (active.get()) {
                var boundary = line(stream)
                while (boundary.isEmpty()) boundary = line(stream)
                check(boundary == "--thermal-field") { "Invalid thermal frame boundary" }
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val header = line(stream); if (header.isEmpty()) break
                    val separator = header.indexOf(':'); check(separator > 0) { "Malformed thermal frame header" }
                    headers[header.substring(0, separator).lowercase()] = header.substring(separator + 1).trim()
                    check(headers.size <= 16) { "Too many thermal frame headers" }
                }
                check(headers["content-length"] == "196608") { "Unsupported thermal frame size" }
                val frame = ByteArray(196608)
                var offset = 0
                while (offset < frame.size && active.get()) {
                    val read = stream.read(frame, offset, frame.size - offset)
                    check(read > 0) { "Network frame ended early" }; offset += read
                }
                val sequence = headers["x-frame-number"]?.toLongOrNull()
                check(sequence != null && sequence >= 0) { "Missing or invalid thermal frame sequence" }
                if (active.get()) receive(frame, sequence)
            }
        }
    }
    private fun line(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read(); check(value >= 0) { "Bridge stream closed" }
            if (value == 10) return bytes.toString(Charsets.US_ASCII).trimEnd('\r')
            bytes.write(value); check(bytes.size() <= 512) { "Oversized bridge header" }
        }
    }
    companion object {
        fun command(streamUrl: String, action: String, high: Boolean) {
            val uri = URI(streamUrl).resolve("control").toURL()
            val connection = uri.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.connectTimeout = 4000; connection.readTimeout = 12000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(JSONObject().put("action", action).put("high", high).toString().toByteArray()) }
                check(connection.responseCode == 204) { "Bridge control failed (HTTP ${connection.responseCode})" }
            } finally { connection.disconnect() }
        }
    }
}
