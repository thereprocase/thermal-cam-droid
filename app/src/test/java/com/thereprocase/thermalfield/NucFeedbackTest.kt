package com.thereprocase.thermalfield

import org.junit.Assert.*
import org.junit.Test

class NucFeedbackTest {
    private val live = CameraUiState(connected = true, frame = FrameTelemetry(frame = 1, ageMs = 40.0),
        nuc = NucFeedback(NucPhase.COMPLETED, 1000))

    @Test fun separatesDeliveryPauseAndRepeatedDataWithoutClaimingShutterMotion() {
        assertEquals("NUC command completed · delivery paused · last image", nucNotice(live.copy(frame = live.frame.copy(ageMs = 800.0)), 1500))
        assertEquals("NUC command completed · radiometric data unchanged", nucNotice(live.copy(frame = live.frame.copy(unchangedMs = 800.0)), 1500))
        assertEquals("NUC command completed", nucNotice(live,1500))
    }

    @Test fun recentContextExpiresButPendingRequestRemainsVisible() {
        assertNotNull(nucNotice(live,3000))
        assertNull(nucNotice(live,3001))
        assertEquals("NUC command pending",nucNotice(live.copy(nuc = NucFeedback(NucPhase.REQUESTED,1000)),12000))
    }

    @Test fun sourceAndFailureContextDoNotImplyCompletedShutterAction() {
        assertNull(nucNotice(live.copy(fixture = true),1500))
        assertNull(nucNotice(live.copy(archive = true),1500))
        assertNull(nucNotice(live.copy(connected = false),1500))
        assertNull(nucNotice(live.copy(busy = true),1500))
        assertNull(nucNotice(live.copy(nuc = NucFeedback(NucPhase.FAILED,1000,"HTTP 503")),1500))
        assertEquals("Last NUC command failed: HTTP 503",nucHistory(NucFeedback(NucPhase.FAILED,1000,"HTTP 503"),1500))
        assertTrue(nucHistory(live.nuc,1500).contains("shutter motion not verified"))
    }
}
