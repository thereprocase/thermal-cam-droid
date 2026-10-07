package com.thereprocase.thermalfield

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

@Composable internal fun ThermalViewport(state: CameraUiState, model: CameraViewModel, ratio: Float, modifier: Modifier, surfaceCreated: () -> Unit, compactScale: Boolean = false) {
    var scaleHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        AndroidView(factory = { context -> SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { model.surface(holder.surface); surfaceCreated() }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { model.updateSurface(holder.surface) }
                override fun surfaceDestroyed(holder: SurfaceHolder) { model.removeSurface(holder.surface) }
            })
        } }, modifier = Modifier.matchParentSize())
        if (state.frame.frame > 0 && state.frame.measurementVersion == state.expectedMeasurementVersion && (state.connected || state.fixture || state.archive)) MeasurementOverlay(state, model, ratio, Modifier.matchParentSize(), if (compactScale) scaleHeightPx.toFloat() else 0f)
        if (state.measurementTool != 0) {
            val tool = when (state.measurementTool) { 1 -> "Spot"; 2 -> "Box"; else -> "Line" }
            Action("$tool · Done", modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) { model.measurementTool(0) }
        }
        if (compactScale) {
            val imageWidth = minOf(maxWidth, maxHeight*ratio)
            val imageHeight = imageWidth/ratio
            val horizontalBar = (maxHeight-imageHeight)/2
            val verticalBar = (maxWidth-imageWidth)/2
            val valid = state.frame.frame > 0 && (state.connected || state.fixture || state.archive) && state.frame.error.isEmpty()
            val lower = if (state.automatic) state.frame.minimum else state.lower.toDouble()
            val upper = if (state.automatic) state.frame.maximum else state.upper.toDouble()
            val scaleHeight = with(density) { scaleHeightPx.toDp() }
            Column(Modifier.offset(x = verticalBar, y = horizontalBar+maxOf(0.dp, imageHeight-scaleHeight)).width(imageWidth)
                .onSizeChanged { scaleHeightPx = it.height }.background(Light.copy(alpha = .90f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label("MIN ${if (valid) temperature(state.frame.minimum, state.fahrenheit) else "—"}", mono = true, size = 11)
                    Label("C ${if (valid) temperature(state.frame.center, state.fahrenheit) else "—"}", mono = true, size = 11)
                    Label("MAX ${if (valid) temperature(state.frame.maximum, state.fahrenheit) else "—"}", mono = true, size = 11)
                }
                PaletteScale(state.palette)
                if (!state.automatic) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label(if (valid || !state.automatic) temperature(lower, state.fahrenheit) else "—", mono = true, size = 11)
                    Label(if (state.automatic) "AUTO SCALE" else "LOCKED SCALE", mono = true, size = 11)
                    Label(if (valid || !state.automatic) temperature(upper, state.fahrenheit) else "—", mono = true, size = 11)
                }
                val qualification = if (state.fixture || state.archive && state.archiveSynthetic) "SYNTHETIC · not a measurement" else "${if (state.archive) "SAVED" else if (state.network) "NETWORK" else "USB"} · ${if (state.corrected) "Corrected ε ${String.format(Locale.US, "%.2f", state.emissivity)}" else "Apparent"} · validation pending"
                Label("${if (state.automatic) "AUTO · " else ""}$qualification", size = 10)
            }
            // Controls occupy existing letterbox space only; their minimum
            // targets must fit without changing sensor-coordinate mapping.
            if (horizontalBar >= 56.dp) {
                Row(Modifier.align(Alignment.BottomCenter).height(horizontalBar).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Action("Rotate +90°", enabled = !state.profileApplying) { model.rotate() }
                    Action("Mirror output", state.mirror, enabled = !state.profileApplying) { model.mirror() }
                }
            } else if (verticalBar >= 64.dp) {
                Column(Modifier.align(Alignment.CenterStart).width(minOf(verticalBar, 140.dp)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Action("+90°", enabled = !state.profileApplying, modifier = Modifier.fillMaxWidth(), accessibilityLabel = "Rotate camera image clockwise 90 degrees") { model.rotate() }
                    Action("180°", state.flip, enabled = !state.profileApplying, modifier = Modifier.fillMaxWidth(), accessibilityLabel = "Flip camera image 180 degrees") { model.flip() }
                }
            }
        }
        if ((!state.connected && !state.fixture && !state.archive) || state.frame.frame == 0L || state.frame.error.isNotEmpty()) {
            Box(Modifier.matchParentSize().background(Light), contentAlignment = Alignment.Center) {
                Label(if (state.busy) "CONNECTING" else "NO SIGNAL", mono = true, size = 18)
            }
        } else if (state.busy || nucNotice(state, state.frame.observedAtMillis) != null || state.frame.ageMs > 300 || state.frame.unchangedMs > 300 && !state.fixture && !state.archive) {
            val badge = nucNotice(state, state.frame.observedAtMillis) ?: when {
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
    val ratio = if (state.renderRotation % 2 == 0) 4f / 3f else 3f / 4f
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ThermalViewport(state, model, ratio, Modifier.fillMaxSize(), surfaceCreated)
        Row(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("Exit full screen") { model.fullScreen(false) }
            Action(if (state.saving) "Saving…" else "Capture", enabled = model.canCapture()) { model.capture() }
        }
        Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().background(Light.copy(alpha = .9f)).padding(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Action("+90°", enabled = !state.profileApplying, accessibilityLabel = "Rotate camera image clockwise 90 degrees") { model.rotate() }
                Action("Flip", state.flip, enabled = !state.profileApplying, accessibilityLabel = "Flip camera image 180 degrees") { model.flip() }
                Action("Lock", state.rotationLocked) { model.rotationLock() }
                Action("NUC", enabled = state.connected && !state.busy,
                    accessibilityLabel = "Send NUC shutter command") { model.command(true) }
            }
            val source = if (state.archive) "SAVED" else if (state.fixture) "DEMO · SYNTHETIC" else if (state.network) "NETWORK" else if (state.selfie) "USB · SELFIE" else "USB"
            Label("$source · ${if (state.corrected) "Corrected" else "Apparent"} · ${temperature(state.frame.center, state.fahrenheit)} · ${if (state.measurementTool == 0) "View" else "Measurement tool active"}", size = 12)
            Label(if (state.fixture || state.archive && state.archiveSynthetic) "Synthetic data · not a measurement" else "Accuracy validation pending", size = 10)
            if (state.captureMessage.isNotEmpty()) Label(state.captureMessage, size = 12)
            if (state.frame.error.isNotEmpty()) Label(state.frame.error, size = 12)
        }
    }
}
