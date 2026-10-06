package com.thereprocase.thermalfield

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

// PNG grayscale16 stores network-order words. Android Bitmap would reduce the
// plane to display precision, so radiometric export uses an explicit encoder.
object RadiometricPng {
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
