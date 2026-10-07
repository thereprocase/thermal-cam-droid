package com.thereprocase.thermalfield

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Loopback-only synthetic transport lets tests count control side effects
// without changing a physical camera or relying on an external network.
internal class MockRadiometricBridge(private val frame: ByteArray) : AutoCloseable {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val stopped = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    val controls = AtomicInteger(0)
    val controlStatus = AtomicInteger(204)
    val streams = AtomicInteger(0)
    val paused = AtomicBoolean(false)
    val address = "http://127.0.0.1:${listener.localPort}/radiometric"
    private val accepting = Thread({
        while (!stopped.get()) {
            val client = try { listener.accept() } catch (_: java.io.IOException) { break }
            clients += client
            Thread({ serve(client) }, "ThermalMockClient").apply { isDaemon = true; start() }
        }
    }, "ThermalMockAccept").apply { isDaemon = true; start() }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 5000
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            val request = input.readLine() ?: return
            while (true) { val line = input.readLine() ?: return; if (line.isEmpty()) break }
            val output = client.getOutputStream()
            if (request.startsWith("POST /control ")) {
                controls.incrementAndGet()
                val status = controlStatus.get()
                output.write("HTTP/1.1 $status ${if (status == 204) "No Content" else "Service Unavailable"}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            } else if (request.startsWith("GET /radiometric ")) {
                streams.incrementAndGet()
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=thermal-field\r\n" +
                    "X-Thermal-Protocol: thermal-field-v1\r\nX-Thermal-Format: yuyv-256x384-u16le-k64\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                var sequence = 0L
                while (!stopped.get()) {
                    if (paused.get()) { Thread.sleep(20); continue }
                    val header = "--thermal-field\r\nContent-Type: application/octet-stream\r\nContent-Length: 196608\r\nX-Frame-Number: ${++sequence}\r\n\r\n"
                    output.write(header.toByteArray(Charsets.US_ASCII)); output.write(frame)
                    output.write(byteArrayOf(13, 10)); output.flush()
                    Thread.sleep(40)
                }
            }
        } catch (_: java.io.IOException) {
            // Closing a source interrupts its socket while this test producer
            // may still be writing the next synthetic frame.
        } finally { clients.remove(client); client.close() }
    }

    override fun close() {
        stopped.set(true); listener.close(); clients.forEach { it.close() }; accepting.join(2000)
    }
}
