package com.thereprocase.thermalfield

import android.app.Activity
import android.app.Instrumentation
import android.app.Application
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

// A framework-only runner exercises Android JSON, MediaStore and the actual
// native renderer without adding test SDKs to the application dependency set.
class ValidationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            validateSavedProvenance()
            validateQueuedControlCancellation()
            result.putString("stream", "Native saved provenance/export and queued control cancellation passed.\n")
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
            validateCaptureFailureCleanup(packet)
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

    private fun validateCaptureFailureCleanup(packet: ByteArray) {
        for ((provider, expectedInserts) in listOf(FaultCaptureProvider(failWrite = 2) to 2, FaultCaptureProvider(refusePublish = 2) to 3)) {
            provider.use {
                var rejected = false
                try { CaptureStore.save(it.contextFor(targetContext), packet, false, false) }
                catch (_: Exception) { rejected = true }
                check(rejected) { "Failed capture was reported as saved" }
                check(it.inserted == expectedInserts) { "Capture did not reach the intended provider failure" }
                check(it.entries.keys == setOf(0L)) { "Failed capture left temporary provider entries" }
                check(0L !in it.deleted && it.deleted.size == it.inserted) { "Cleanup touched an unrelated entry or missed a created entry" }
            }
        }
        FaultCaptureProvider().use {
            val capture = CaptureStore.save(it.contextFor(targetContext), packet, false, true)
            check(capture.rawPreferred && it.inserted == 3 && it.deleted.isEmpty())
            check(it.entries.filterKeys { id -> id != 0L }.values.all { values -> values.getAsInteger(android.provider.MediaStore.MediaColumns.IS_PENDING) == 0 })
        }
        FaultCaptureProvider(failWrite = 2, refuseDelete = 1).use {
            var message = ""
            try { CaptureStore.save(it.contextFor(targetContext), packet, false, false) }
            catch (error: Exception) { message = error.message ?: "" }
            check(message.contains("cleanup could not be confirmed for 1 file")) { "Unconfirmed cleanup was hidden from capture failure" }
            check(it.entries.keys == setOf(0L, 1L) && 0L !in it.deleted)
        }
    }

    private fun validateQueuedControlCancellation() {
        val preferences = targetContext.getSharedPreferences("display", android.content.Context.MODE_PRIVATE)
        val hadAddress = preferences.contains("networkUrl")
        val originalAddress = preferences.getString("networkUrl", null)
        val frame = targetContext.assets.open("fixture.yuyv").use { it.readBytes() }
        val consumer = HandlerThread("ThermalControlValidationSurface").apply { start() }
        val reader = ImageReader.newInstance(256, 192, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
        val store = ViewModelStore()
        lateinit var model: CameraViewModel
        var executor: ExecutorService? = null
        val release = CountDownLatch(1)
        try {
            MockRadiometricBridge(frame).use { first -> MockRadiometricBridge(frame).use { second ->
                runOnMainSync {
                    val provider = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(targetContext.applicationContext as Application))
                    model = provider[CameraViewModel::class.java]
                    model.surface(reader.surface)
                    model.network(first.address)
                    model.foreground()
                }
                fun awaitConnected(address: String) {
                    val deadline = SystemClock.elapsedRealtime() + 8000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val state = model.state.value
                        if (state.network && state.networkUrl == address && state.connected && !state.busy && state.frame.received >= 10 && state.frame.frame > 0) return
                        SystemClock.sleep(20)
                    }
                    error("Synthetic network source did not become ready")
                }
                awaitConnected(first.address)
                val unchangedDeadline = SystemClock.elapsedRealtime() + 5000
                while (model.state.value.frame.unchangedMs < 700 && SystemClock.elapsedRealtime() < unchangedDeadline) SystemClock.sleep(20)
                check(model.state.value.frame.unchangedMs >= 700 && model.state.value.frame.ageMs < 300) { "Repeated content was not distinguished from stalled transport" }
                first.paused.set(true)
                val stallDeadline = SystemClock.elapsedRealtime() + 5000
                while (model.state.value.frame.ageMs < 700 && SystemClock.elapsedRealtime() < stallDeadline) SystemClock.sleep(20)
                val stalled = model.state.value.frame
                check(stalled.ageMs >= 700 && model.state.value.connected) { "Live socket pause did not produce stalled-frame telemetry" }
                first.paused.set(false)
                val resumeDeadline = SystemClock.elapsedRealtime() + 5000
                while ((model.state.value.frame.received <= stalled.received || model.state.value.frame.ageMs >= 300) && SystemClock.elapsedRealtime() < resumeDeadline) SystemClock.sleep(20)
                check(model.state.value.frame.received > stalled.received && model.state.value.frame.ageMs < 300) { "Frame delivery did not recover after a bounded synthetic pause" }
                // Occupy the real command executor to force the queued case;
                // ordinary gesture timing cannot reliably reproduce this race.
                val field = CameraViewModel::class.java.getDeclaredField("worker").apply { isAccessible = true }
                executor = field.get(model) as ExecutorService
                val blocked = CountDownLatch(1)
                executor!!.execute { blocked.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                check(blocked.await(2, TimeUnit.SECONDS))
                runOnMainSync {
                    check(model.state.value.network && model.state.value.networkUrl == first.address)
                    model.command(nuc = true); model.network(second.address)
                }
                release.countDown()
                awaitConnected(second.address)
                check(first.controls.get() == 0 && second.controls.get() == 0) { "Cancelled queued control reached a bridge" }
                runOnMainSync {
                    check(model.state.value.network && model.state.value.networkUrl == second.address)
                    model.command(nuc = false, high = true)
                }
                val deadline = SystemClock.elapsedRealtime() + 5000
                while ((!model.state.value.gainKnown || model.state.value.busy) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                check(first.controls.get() == 0 && second.controls.get() == 1 && model.state.value.gainKnown && !model.state.value.busy) { "Current control did not reach its selected bridge" }
                runOnMainSync { model.fixture() }
                val fixtureDeadline = SystemClock.elapsedRealtime() + 5000
                while ((!model.state.value.fixture || model.state.value.busy || model.state.value.frame.frame == 0L) && SystemClock.elapsedRealtime() < fixtureDeadline) SystemClock.sleep(20)
                val fixtureState = model.state.value
                check(fixtureState.fixture && !fixtureState.busy && fixtureState.frame.frame > 0 && !fixtureState.network && !fixtureState.gainKnown) { "Fixture retained a network source or camera gain label" }
            } }
        } finally {
            release.countDown()
            runOnMainSync { store.clear() }
            executor?.awaitTermination(5, TimeUnit.SECONDS)
            preferences.edit().apply { if (hadAddress) putString("networkUrl", originalAddress) else remove("networkUrl") }.commit()
            reader.setOnImageAvailableListener(null, null)
            consumer.quitSafely(); consumer.join(2000); reader.close()
        }
    }
}
