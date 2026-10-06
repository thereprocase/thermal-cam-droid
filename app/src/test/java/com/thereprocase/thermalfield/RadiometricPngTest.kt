package com.thereprocase.thermalfield

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
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
}
