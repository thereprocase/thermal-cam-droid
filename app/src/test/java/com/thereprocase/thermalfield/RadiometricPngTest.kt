package com.thereprocase.thermalfield

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RadiometricPngTest {
    @Test fun independentDecoderPreservesEveryWord() {
        val frame = ByteArray(196608)
        for (i in 0 until 49152) {
            val value = (i * 7919) and 65535
            frame[98304 + i * 2] = value.toByte()
            frame[98304 + i * 2 + 1] = (value ushr 8).toByte()
        }
        val decoded = ImageIO.read(ByteArrayInputStream(RadiometricPng.encode(frame)))
        assertEquals(16, decoded.sampleModel.sampleSize[0])
        assertEquals(256, decoded.width); assertEquals(192, decoded.height)
        for (i in 0 until 49152) assertEquals((i * 7919) and 65535, decoded.raster.getSample(i % 256, i / 256, 0))
    }
    @Test fun incompleteFramesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RadiometricPng.encode(ByteArray(196607)) }
    }
    @Test fun independentEncoderReopensWithoutLosingPrecision() {
        val image = BufferedImage(256, 192, BufferedImage.TYPE_USHORT_GRAY)
        for (i in 0 until 49152) image.raster.setSample(i % 256, i / 256, 0, (i * 7919) and 65535)
        val output = ByteArrayOutputStream(); ImageIO.write(image, "png", output)
        val decoded = RadiometricPng.decode(output.toByteArray())
        for (i in 0 until 49152) assertEquals((i * 7919) and 65535, (decoded[98304+i*2].toInt() and 255) or ((decoded[98305+i*2].toInt() and 255) shl 8))
        assertArrayEquals(decoded, RadiometricPng.decode(RadiometricPng.encode(decoded)))
    }
    @Test fun malformedAndNonRadiometricImagesAreRejected() {
        val valid = RadiometricPng.encode(ByteArray(196608))
        val corrupt = valid.clone(); corrupt[29] = (corrupt[29].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { RadiometricPng.decode(corrupt) }
        assertThrows(IllegalArgumentException::class.java) { RadiometricPng.decode(valid.copyOf(valid.size - 1)) }
        val output = ByteArrayOutputStream(); ImageIO.write(BufferedImage(256,192,BufferedImage.TYPE_BYTE_GRAY), "png", output)
        assertThrows(IllegalArgumentException::class.java) { RadiometricPng.decode(output.toByteArray()) }
    }
}
