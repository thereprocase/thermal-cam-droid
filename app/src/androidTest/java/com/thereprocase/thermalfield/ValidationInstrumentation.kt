package com.thereprocase.thermalfield

import android.app.Activity
import android.app.Instrumentation
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

// A framework-only runner exercises Android JSON, MediaStore and the actual
// native renderer without adding test SDKs to the application dependency set.
class ValidationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            validateSavedProvenance()
            result.putString("stream", "Native saved provenance, lossless export and MediaStore reopen passed.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "Validation failed: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun validateSavedProvenance() {
        val bridge = NativeBridge()
        val engine = bridge.create()
        val consumer = HandlerThread("ThermalValidationSurface").apply { start() }
        val reader = ImageReader.newInstance(256, 192, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
        val temporary = mutableListOf<SavedCapture>()
        try {
            val frame = targetContext.assets.open("fixture.yuyv").use { it.readBytes() }
            val originalTime = 1_700_000_000_000_000_000L
            val before = intArrayOf(32, 300, 300, 128, 128, 0)
            val configured = intArrayOf(32, 300, 300, 128, 128, 1)
            bridge.surface(engine, reader.surface)
            bridge.configure(engine, 0, false, 0, false, true, 20f, 30f)
            bridge.correction(engine, 0.96, 20.0, true)
            bridge.archive(engine, frame, originalTime, "fixture", 1, "SYNTHETIC-TEST-FIRMWARE", before, configured)
            awaitFrame(bridge, engine)
            val packet = bridge.capture(engine)
            val length = ByteBuffer.wrap(packet, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            val metadata = JSONObject(String(packet, 4, length, Charsets.UTF_8))
            check(metadata.getLong("timestamp_unix_ns") == originalTime)
            val context = metadata.getJSONObject("identity").getJSONObject("original_device_context")
            check(context.getString("firmware") == "SYNTHETIC-TEST-FIRMWARE")
            check(!context.getBoolean("physical_baseline_verified"))
            for (i in 0 until 6) {
                check(context.getJSONArray("original_properties").getInt(i) == before[i])
                check(context.getJSONArray("configured_properties").getInt(i) == configured[i])
            }
            check(!context.has("serial") && !context.has("source_address"))
            val saved = CaptureStore.save(targetContext, packet, false, false).also { temporary += it }
            check(CaptureCatalog.list(targetContext).any { it.capture.id == saved.id })
            val loaded = CaptureCatalog.load(targetContext, saved)
            check(loaded.composite.copyOfRange(98304, 196608).contentEquals(frame.copyOfRange(98304, 196608)))
            val restored = loaded.metadata.getJSONObject("identity").getJSONObject("original_device_context")
            bridge.stop(engine)
            bridge.archive(engine, loaded.composite, loaded.metadata.getLong("timestamp_unix_ns"), "fixture", 1,
                restored.getString("firmware"), IntArray(6) { restored.getJSONArray("original_properties").getInt(it) },
                IntArray(6) { restored.getJSONArray("configured_properties").getInt(it) })
            awaitFrame(bridge, engine)
            val repeated = JSONObject(bridge.summary(engine)).getJSONObject("identity").getJSONObject("original_device_context")
            check(repeated.toString() == restored.toString())
            var rejected = false
            try { bridge.archive(engine, frame, originalTime, "fixture", 1, "", intArrayOf(1), configured) }
            catch (_: IllegalStateException) { rejected = true }
            check(rejected) { "Malformed register array was accepted" }
        } finally {
            temporary.forEach { capture -> listOf(capture.rendered, capture.raw, capture.metadata).forEach { uri ->
                targetContext.contentResolver.delete(uri, null, null)
            } }
            bridge.surface(engine, null)
            bridge.stop(engine)
            bridge.destroy(engine)
            reader.setOnImageAvailableListener(null, null)
            consumer.quitSafely()
            consumer.join(2000)
            reader.close()
        }
    }

    private fun awaitFrame(bridge: NativeBridge, engine: Long) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (JSONObject(bridge.summary(engine)).optLong("frame") > 0) return
            SystemClock.sleep(20)
        }
        error("Native renderer did not present a saved frame within five seconds")
    }
}
