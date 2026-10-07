package com.thereprocase.thermalfield

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerOrientationTest {
    @Test fun screenRotationDoesNotTransformAttachedCameraImage() {
        for (manual in 0..3) for (display in 0..3) for (flip in listOf(false, true)) {
            val state = CameraUiState(rotation = manual, displayRotation = display, flip = flip)
            assertEquals((manual + (if (flip) 2 else 0)) % 4, state.renderRotation)
        }
    }

    @Test fun independentSourcesKeepTheirOwnOrientation() {
        for (display in 0..3) {
            val state = CameraUiState(rotation = 1, displayRotation = display, flip = true)
            assertEquals(3, state.copy(archive = true).renderRotation)
            assertEquals(3, state.copy(network = true).renderRotation)
            assertEquals(3, state.copy(fixture = true).renderRotation)
        }
    }
}
