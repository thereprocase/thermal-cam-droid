package com.thereprocase.thermalfield

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private fun imageBounds(width: Float, height: Float, ratio: Float): FloatArray {
    val w = minOf(width, height * ratio); val h = w / ratio
    return floatArrayOf((width - w) / 2, (height - h) / 2, w, h)
}

@Composable internal fun MeasurementOverlay(state: CameraUiState, model: CameraViewModel, ratio: Float, modifier: Modifier, reservedBottomPx: Float = 0f) {
    var start by remember { mutableStateOf<Offset?>(null) }; var end by remember { mutableStateOf<Offset?>(null) }
    val context = LocalContext.current
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = context.resources.getFont(R.font.plex_mono_regular) } }
    val tool = state.measurementTool
    val gesture = if (tool == 1) Modifier.pointerInput(tool, state.selectedMeasurement, ratio, state.renderRotation, state.previewMirrored) {
        detectTapGestures { offset ->
            val b = imageBounds(size.width.toFloat(), size.height.toFloat(), ratio)
            val x = (offset.x - b[0]) / b[2]; val y = (offset.y - b[1]) / b[3]
            if (x in 0f..1f && y in 0f..1f && state.frame.frame > 0) model.placeMeasurement(x.toDouble(), y.toDouble(), x.toDouble(), y.toDouble())
        }
    } else if (tool in 2..3) Modifier.pointerInput(tool, state.selectedMeasurement, ratio, state.renderRotation, state.previewMirrored) {
        fun normalized(offset: Offset): Offset {
            val b = imageBounds(size.width.toFloat(), size.height.toFloat(), ratio)
            return Offset(((offset.x - b[0]) / b[2]).coerceIn(0f, 1f), ((offset.y - b[1]) / b[3]).coerceIn(0f, 1f))
        }
        // Preserve the actual finger-down pixel. Starting after touch slop
        // shifted box/line origins during the first Pixel gesture test.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val offset = down.position
            val b = imageBounds(size.width.toFloat(), size.height.toFloat(), ratio)
            if (offset.x in b[0]..b[0]+b[2] && offset.y in b[1]..b[1]+b[3]) {
                down.consume(); start = normalized(offset); end = start
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume(); end = normalized(change.position)
                        if (!change.pressed) {
                            val a = start!!; val finish = end!!
                            model.placeMeasurement(a.x.toDouble(), a.y.toDouble(), finish.x.toDouble(), finish.y.toDouble()); break
                        }
                    }
                } finally { start = null; end = null }
            }
        }
    } else Modifier
    Canvas(modifier.then(gesture)) {
        val bounds = imageBounds(size.width, size.height, ratio)
        fun display(p: Offset) = Offset(bounds[0] + p.x * bounds[2], bounds[1] + p.y * bounds[3])
        paint.textSize = 12.sp.toPx()
        val metrics = paint.fontMetrics
        val padding = 3.dp.toPx()
        val labelHeight = metrics.descent-metrics.ascent+padding*2
        val scaleHeight = reservedBottomPx
        val area = LabelRect(bounds[0]+padding,bounds[1]+padding,bounds[0]+bounds[2]-padding,bounds[1]+bounds[3]-scaleHeight-padding)
        val labels = state.frame.measurements.map { measurement ->
            display(Offset(measurement.x0.toFloat(), measurement.y0.toFloat())) to
                "${measurement.label}${if (measurement.kind == 1) "" else " AVG"} ${temperature(measurement.average, state.fahrenheit)}"
        }
        fun layout(region: LabelRect): List<LabelRect?> {
            val occupied = mutableListOf<LabelRect>()
            return labels.map { (anchor,text) ->
                placeLabel(region,paint.measureText(text)+padding*2,labelHeight,anchor.x,anchor.y,occupied,padding)
                    .also { if (it != null) occupied += it }
            }
        }
        var rectangles = layout(area)
        if (rectangles.any { it == null }) {
            // Reserve the notice row only when readable labels cannot all fit.
            rectangles = layout(area.copy(top = area.top+labelHeight+padding))
        }
        val hidden = rectangles.count { it == null }
        for ((index,entry) in labels.withIndex()) {
            val p = entry.first
            val rectangle = rectangles[index] ?: continue
            val canvas = drawContext.canvas.nativeCanvas
            val endX = p.x.coerceIn(rectangle.left,rectangle.right)
            val endY = p.y.coerceIn(rectangle.top,rectangle.bottom)
            // Displaced labels retain an explicit link to their sensor anchor;
            // contrast comes from paired strokes rather than palette color.
            paint.color = android.graphics.Color.BLACK; paint.strokeWidth = 3.dp.toPx()
            canvas.drawLine(p.x,p.y,endX,endY,paint)
            paint.color = android.graphics.Color.WHITE; paint.strokeWidth = 1.dp.toPx()
            canvas.drawLine(p.x,p.y,endX,endY,paint)
        }
        // Draw every leader before any label, so later leaders cannot cross
        // previously painted text in dense layouts.
        for ((index,entry) in labels.withIndex()) {
            val rectangle = rectangles[index] ?: continue
            paint.color = android.graphics.Color.argb(210,0,0,0)
            drawContext.canvas.nativeCanvas.drawRect(rectangle.left,rectangle.top,rectangle.right,rectangle.bottom,paint)
            paint.color = android.graphics.Color.WHITE
            drawContext.canvas.nativeCanvas.drawText(entry.second,rectangle.left+padding,rectangle.top+padding-metrics.ascent,paint)
        }
        if (hidden > 0) {
            // A small viewport cannot fit every readable label. Keep the
            // measurement values in the panel and disclose the omission.
            val notice = "$hidden labels hidden · Controls → Measure"
            val x = bounds[0]+padding
            val baseline = bounds[1]+padding-metrics.ascent
            paint.color = android.graphics.Color.argb(230,0,0,0)
            drawContext.canvas.nativeCanvas.drawRect(x-padding,bounds[1],minOf(size.width,x+paint.measureText(notice)+padding),baseline+metrics.descent+padding,paint)
            paint.color = android.graphics.Color.WHITE
            drawContext.canvas.nativeCanvas.drawText(notice,x,baseline,paint)
        }
        val a = start; val b = end
        if (a != null && b != null) {
            val p = display(a); val q = display(b)
            if (tool == 2) drawRect(Color.White, Offset(minOf(p.x,q.x),minOf(p.y,q.y)), androidx.compose.ui.geometry.Size(kotlin.math.abs(p.x-q.x),kotlin.math.abs(p.y-q.y)), style = Stroke(3f))
            else drawLine(Color.White, p, q, 3f)
        }
    }
}

@Composable internal fun MeasurementToolbar(state: CameraUiState, model: CameraViewModel) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("View", "Spot", "Box", "Line").forEachIndexed { index, label ->
            Action(label, state.measurementTool == index, enabled = !state.profileApplying, modifier = Modifier.weight(1f)) { model.measurementTool(index) }
        }
    }
    if (state.measurementTool != 0) Label("${if (state.selectedMeasurement == 0) "Place" else "Replace ${state.selectedMeasurement}"} ${if (state.measurementTool == 1) "spot: tap image" else "${if (state.measurementTool == 2) "box" else "line"}: drag image"}", Modifier.padding(horizontal = 10.dp), mono = true, size = 12)
}

@Composable internal fun MeasurementsPane(state: CameraUiState, model: CameraViewModel, editIsotherm: () -> Unit) {
    Pane("MEASUREMENTS / SENSOR PIXELS") {
        if (state.frame.measurements.isEmpty()) Label("Choose Spot, Box or Line above the image. Geometry stays in sensor coordinates through rotation and mirroring.", Modifier.padding(12.dp), size = 12)
        for (m in state.frame.measurements) Column(Modifier.padding(10.dp)) {
            Label("${m.label} · ${if (m.kind == 1) temperature(m.average, state.fahrenheit) else "MIN ${temperature(m.minimum, state.fahrenheit)} · AVG ${temperature(m.average, state.fahrenheit)} · MAX ${temperature(m.maximum, state.fahrenheit)}"}", mono = true, size = 12)
            if (m.invalid > 0) Label("${m.valid} valid / ${m.invalid} invalid samples", size = 12)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Action("Edit", modifier = Modifier.weight(1f)) { model.measurementTool(m.kind, m.id) }
                Action("Delete", modifier = Modifier.weight(1f)) { model.deleteMeasurement(m.id) }
                if (m.kind == 1) {
                    Action("Δ A", state.deltaFirst == m.id, modifier = Modifier.weight(1f)) { model.measurementOptions(first = m.id) }
                    Action("Δ B", state.deltaSecond == m.id, modifier = Modifier.weight(1f)) { model.measurementOptions(second = m.id) }
                }
            }
            if (m.kind == 3) LineProfile(m.profile, state.fahrenheit)
        }
        val delta = if (state.fahrenheit) state.frame.delta * 1.8 else state.frame.delta
        Label("ΔT A − B ${if (delta.isFinite()) String.format(Locale.US, "%.1f Δ°%s", delta, if (state.fahrenheit) "F" else "C") else "— · select two valid spots"}", Modifier.padding(12.dp), mono = true)
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Action("Clear measurements", enabled = state.frame.measurements.isNotEmpty(), modifier = Modifier.weight(1f)) { model.deleteMeasurement(0) }
            Action("Isotherm…", modifier = Modifier.weight(1f), action = editIsotherm)
        }
        val isotherm = isothermDescription(state.isothermMode, state.isothermLower.toDouble(), state.isothermUpper.toDouble(), state.fahrenheit)
        Label("Isotherm $isotherm${if (state.isothermMode == 0) "" else " · ${state.frame.isothermPixels} matching sensor pixels"}", Modifier.padding(12.dp), mono = true, size = 12)
    }
}

@Composable private fun LineProfile(values: List<Double>, fahrenheit: Boolean) {
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty()) { Label("Line profile: no valid samples", size = 12); return }
    val lower = finite.min(); val upper = finite.max()
    Label("A → B · ${values.size} nearest-pixel samples · ${temperature(lower, fahrenheit)} to ${temperature(upper, fahrenheit)}", mono = true, size = 12)
    Canvas(Modifier.fillMaxWidth().height(100.dp).background(Light)) {
        val span = maxOf(upper - lower, .1)
        fun point(index: Int) = Offset(8 + (size.width - 16) * index / maxOf(1, values.lastIndex), size.height - 8 - ((values[index] - lower) / span * (size.height - 16)).toFloat())
        for (index in 1 until values.size) if (values[index-1].isFinite() && values[index].isFinite()) drawLine(Blue, point(index-1), point(index), 2f)
        if (values.size == 1) drawCircle(Blue, 3f, point(0))
    }
}
