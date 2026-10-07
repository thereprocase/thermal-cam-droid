package com.thereprocase.thermalfield

import org.junit.Assert.*
import org.junit.Test

class StreamRecoveryTest {
    private val stalled = CameraUiState(connected = true, frame = FrameTelemetry(frame = 1, ageMs = 3000.0))

    @Test fun recoveryWaitsForProlongedTransportStall() {
        assertTrue(canRestartStalledSource(stalled))
        assertFalse(canRestartStalledSource(stalled.copy(frame = stalled.frame.copy(ageMs = 2999.0))))
        assertFalse(canRestartStalledSource(stalled.copy(frame = stalled.frame.copy(ageMs = 40.0, unchangedMs = 10000.0))))
        assertFalse(canRestartStalledSource(stalled.copy(frame = stalled.frame.copy(ageMs = Double.NaN))))
    }

    @Test fun excludesSourcesAndStatesThatCannotBeRestartedHere() {
        for (state in listOf(stalled.copy(fixture = true), stalled.copy(archive = true), stalled.copy(connected = false),
            stalled.copy(busy = true), stalled.copy(profileApplying = true), stalled.copy(saving = true),
            stalled.copy(frame = stalled.frame.copy(frame = 0)), stalled.copy(frame = stalled.frame.copy(error = "Display error")))) {
            assertFalse(canRestartStalledSource(state))
        }
        assertTrue(canRestartStalledSource(stalled.copy(network = true)))
    }
}
