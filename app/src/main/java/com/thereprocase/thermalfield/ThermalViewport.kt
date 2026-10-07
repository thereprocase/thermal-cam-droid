package com.thereprocase.thermalfield

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

@Composable internal fun ThermalViewport(state: CameraUiState, model: CameraViewModel, ratio: Float, modifier: Modifier, surfaceCreated: () -> Unit) {
    Box(modifier) {
        AndroidView(factory = { context -> SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { model.surface(holder.surface); surfaceCreated() }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { model.updateSurface(holder.surface) }
                override fun surfaceDestroyed(holder: SurfaceHolder) { model.removeSurface(holder.surface) }
            })
        } }, modifier = Modifier.matchParentSize())
        if (state.frame.frame > 0 && (state.connected || state.fixture || state.archive)) MeasurementOverlay(state, model, ratio, Modifier.matchParentSize())
        if ((!state.connected && !state.fixture && !state.archive) || state.frame.frame == 0L || state.frame.error.isNotEmpty()) {
            Box(Modifier.matchParentSize().background(Light), contentAlignment = Alignment.Center) {
                Label(if (state.busy) "CONNECTING" else "NO SIGNAL", mono = true, size = 18)
            }
        } else if (state.busy || state.frame.ageMs > 300 || state.frame.unchangedMs > 300 && !state.fixture && !state.archive) {
            val badge = when {
                state.busy -> state.status
                state.frame.ageMs > 300 -> "FRAME STALLED · LAST IMAGE"
                else -> "LIVE TRANSPORT · DATA UNCHANGED"
            }
            Label(badge, Modifier.align(Alignment.TopCenter).background(Color(0xfffff4dc)).padding(8.dp), mono = true, size = 12)
            if (canRestartStalledSource(state)) {
                Column(Modifier.align(Alignment.Center).background(Light).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("No frames for at least 3 seconds · showing the last image", size = 12)
                    Action("Reconnect stream") { model.restartStalledSource() }
                }
            }
        }
    }
}

@Composable internal fun FullScreenView(state: CameraUiState, model: CameraViewModel, surfaceCreated: () -> Unit) {
    val ratio = if ((state.rotation + if (state.flip) 2 else 0) % 2 == 0) 4f / 3f else 3f / 4f
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ThermalViewport(state, model, ratio, Modifier.fillMaxSize(), surfaceCreated)
        Row(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("Exit full screen") { model.fullScreen(false) }
            Action(if (state.saving) "Saving…" else "Capture", enabled = model.canCapture()) { model.capture() }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().background(Light).padding(8.dp)) {
            val valid = state.frame.frame > 0 && (state.connected || state.fixture || state.archive) && state.frame.error.isEmpty()
            val lower = if (state.automatic) state.frame.minimum else state.lower.toDouble()
            val upper = if (state.automatic) state.frame.maximum else state.upper.toDouble()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Label("MIN ${if (valid) temperature(state.frame.minimum, state.fahrenheit) else "—"}", mono = true, size = 12)
                Label("C ${if (valid) temperature(state.frame.center, state.fahrenheit) else "—"}", mono = true, size = 12)
                Label("MAX ${if (valid) temperature(state.frame.maximum, state.fahrenheit) else "—"}", mono = true, size = 12)
            }
            PaletteScale(state.palette)
            Label("${if (state.automatic) "AUTO" else "LOCKED"} ${if (valid || !state.automatic) temperature(lower, state.fahrenheit) else "—"} — ${if (valid || !state.automatic) temperature(upper, state.fahrenheit) else "—"}", mono = true, size = 12)
            val source = if (state.archive) "SAVED FRAME${if (state.archiveSynthetic) " · SYNTHETIC" else ""}" else if (state.fixture) "DEMO · SYNTHETIC" else if (state.network) "NETWORK" else "USB"
            val modelLabel = if (state.corrected) "Corrected ε ${String.format(Locale.US,"%.3f",state.emissivity)} · R ${temperature(state.reflectedCelsius,state.fahrenheit)}" else "Apparent"
            Label("$source · $modelLabel${if (state.fixture || state.archiveSynthetic && state.archive) " · not a measurement" else " · validation pending"}", size = 12)
            if (state.captureMessage.isNotEmpty()) Label(state.captureMessage, mono = true, size = 12)
            if (!valid) Label(state.frame.error.ifEmpty { state.status }, mono = true, size = 12)
        }
    }
}
