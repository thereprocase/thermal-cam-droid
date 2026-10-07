package com.thereprocase.thermalfield

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// PNG grayscale16 stores network-order words. Android Bitmap would reduce the
// plane to display precision, so radiometric export uses an explicit encoder.
object RadiometricPng {
    fun decode(png: ByteArray): ByteArray {
        require(png.size in 57..2_000_000 && png.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "Not a supported radiometric PNG" }
        val input = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN)
        input.position(8)
        val compressed = ByteArrayOutputStream()
        var header = false; var ended = false; var dataSeen = false
        while (input.remaining() >= 12 && !ended) {
            val length = input.int
            require(length >= 0 && length.toLong() + 8 <= input.remaining()) { "Truncated PNG chunk" }
            val type = ByteArray(4).also { input.get(it) }; val data = ByteArray(length).also { input.get(it) }
            val expectedCrc = input.int
            require(CRC32().apply { update(type); update(data) }.value.toInt() == expectedCrc) { "PNG checksum mismatch" }
            val name = String(type, Charsets.US_ASCII)
            require(header || name == "IHDR") { "Missing PNG header" }
            when (name) {
                "IHDR" -> {
                    require(!header && length == 13) { "Invalid PNG header" }
                    val info = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                    require(info.int == 256 && info.int == 192 && data.copyOfRange(8, 13).contentEquals(byteArrayOf(16, 0, 0, 0, 0))) { "Expected 256×192 non-interlaced grayscale16 PNG" }
                    header = true
                }
                "IDAT" -> { dataSeen = true; compressed.write(data) }
                "IEND" -> { require(length == 0 && dataSeen) { "Incomplete radiometric PNG" }; ended = true }
                else -> require(type[0].toInt() and 32 != 0) { "Unsupported critical PNG chunk" }
            }
        }
        require(ended && !input.hasRemaining()) { "PNG ended early or has trailing data" }
        val expectedSize = 513 * 192
        val scanlines = InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use { stream ->
            val bytes = stream.readNBytes(expectedSize + 1)
            require(bytes.size == expectedSize && stream.read() == -1) { "Unexpected radiometric PNG decompressed size" }
            bytes
        }
        val composite = ByteArray(196608)
        var previous = ByteArray(512)
        for (y in 0 until 192) {
            val offset = y * 513; val filter = scanlines[offset].toInt() and 255
            require(filter in 0..4) { "Unsupported PNG row filter" }
            val row = ByteArray(512)
            for (i in row.indices) {
                val a = if (i >= 2) row[i-2].toInt() and 255 else 0
                val b = previous[i].toInt() and 255
                val c = if (i >= 2) previous[i-2].toInt() and 255 else 0
                val predictor = when (filter) {
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    4 -> { val p = a + b - c; val pa = kotlin.math.abs(p-a); val pb = kotlin.math.abs(p-b); val pc = kotlin.math.abs(p-c); if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
                    else -> 0
                }
                row[i] = ((scanlines[offset+i+1].toInt() and 255) + predictor).toByte()
            }
            for (x in 0 until 256) { composite[98304+y*512+x*2] = row[x*2+1]; composite[98304+y*512+x*2+1] = row[x*2] }
            previous = row
        }
        return composite
    }

    fun encode(composite: ByteArray): ByteArray {
        require(composite.size == 196608) { "A complete 256×384 YUYV frame is required" }
        val scanlines = ByteArrayOutputStream(98500)
        for (y in 0 until 192) {
            scanlines.write(0)
            for (x in 0 until 256) {
                val offset = 98304 + (y * 256 + x) * 2
                scanlines.write(composite[offset + 1].toInt() and 255)
                scanlines.write(composite[offset].toInt() and 255)
            }
        }
        val compressed = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_SPEED)
        try { DeflaterOutputStream(compressed, deflater).use { it.write(scanlines.toByteArray()) } }
        finally { deflater.end() }
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { it.writeInt(256); it.writeInt(192); it.write(byteArrayOf(16, 0, 0, 0, 0)) }
        chunk(output, "IHDR", header.toByteArray())
        chunk(output, "IDAT", compressed.toByteArray())
        chunk(output, "IEND", byteArrayOf())
        return output.toByteArray()
    }

    private fun chunk(output: ByteArrayOutputStream, name: String, data: ByteArray) {
        val type = name.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(data) }
        val stream = DataOutputStream(output)
        stream.writeInt(data.size); stream.write(type); stream.write(data); stream.writeInt(crc.value.toInt())
    }
}
