package com.thereprocase.thermalfield

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerOrientationTest {
    @Test fun attachedMountsReverseRollButAgreeInBothPortraits() {
        for (manual in 0..3) for (display in 0..3) for (flip in listOf(false, true)) {
            val away = CameraUiState(rotation = manual, displayRotation = display, flip = flip)
            val toward = away.copy(selfie = true)
            val extra = if (flip) 2 else 0
            assertEquals((manual + display + extra) % 4, away.renderRotation)
            assertEquals((manual - display + extra + 4) % 4, toward.renderRotation)
            assertEquals(if (display % 2 == 0) 0 else 2, (away.renderRotation - toward.renderRotation + 4) % 4)
            assertEquals(away.renderRotation, away.copy(fullScreen = true).renderRotation)
            assertEquals(toward.renderRotation, toward.copy(fullScreen = true).renderRotation)
        }
    }

    @Test fun independentSourcesKeepTheirOwnOrientationAndMirror() {
        for (display in 0..3) for (mirror in listOf(false, true)) {
            val state = CameraUiState(rotation = 1, displayRotation = display, flip = true, selfie = true, mirror = mirror)
            for (source in listOf(state.copy(archive = true), state.copy(network = true), state.copy(fixture = true))) {
                assertEquals(3, source.renderRotation)
                assertEquals(mirror, source.previewMirrored)
            }
        }
    }

    @Test fun selfiePreviewMirrorIsIndependentOfExplicitExportMirror() {
        for (selfie in listOf(false, true)) for (mirror in listOf(false, true)) {
            val state = CameraUiState(selfie = selfie, mirror = mirror)
            assertEquals(mirror xor selfie, state.previewMirrored)
            assertEquals(mirror, state.mirror)
        }
    }
}
