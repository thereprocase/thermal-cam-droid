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
    private var measurementWorkload = false
    override fun onCreate(arguments: Bundle?) { measurementWorkload = arguments?.getString("workload") == "true"; super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            validateSavedProvenance()
            validateQueuedControlCancellation()
            validateCaptureDuringGalleryWork()
            val workload = if (measurementWorkload) validateMeasurementWorkload().toString() else null
            result.putString("stream", "Native saved provenance/export, queued control cancellation and capture/gallery isolation passed.\n" +
                if (workload != null) "Synthetic workload: $workload\n" else "")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "Validation failed: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun validateCaptureDuringGalleryWork() {
        val consumer = HandlerThread("ThermalGalleryValidationSurface").apply { start() }
        val reader = ImageReader.newInstance(256, 192, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
        val store = ViewModelStore()
        val releaseGallery = CountDownLatch(1)
        val releaseCapture = CountDownLatch(1)
        val galleryBlocked = CountDownLatch(1)
        lateinit var model: CameraViewModel
        var previous: SavedCapture? = null
        var captureRequested = false
        var saved: SavedCapture? = null
        var galleryExecutor: ExecutorService? = null
        var usbExecutor: ExecutorService? = null
        var captureExecutor: ExecutorService? = null
        try {
            runOnMainSync {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(targetContext.applicationContext as Application))[CameraViewModel::class.java]
                model.surface(reader.surface)
                model.fixture()
                model.foreground()
            }
            fun executor(name: String) = CameraViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(model) as ExecutorService
            galleryExecutor = executor("galleryWorker")
            usbExecutor = executor("worker")
            captureExecutor = executor("captureWorker")
            // Hold the real gallery executor after its startup listing. This
            // proves isolation without assuming a particular MediaStore speed.
            galleryExecutor.execute { galleryBlocked.countDown(); check(releaseGallery.await(25, TimeUnit.SECONDS)) }
            check(galleryBlocked.await(5, TimeUnit.SECONDS)) { "Gallery worker did not reach the controlled barrier" }
            val readyDeadline = SystemClock.elapsedRealtime() + 5000
            while (!model.canCapture() && SystemClock.elapsedRealtime() < readyDeadline) SystemClock.sleep(20)
            check(model.canCapture() && model.state.value.fixture) { "Synthetic capture source did not become ready" }
            previous = model.state.value.lastCapture
            runOnMainSync { model.refreshGallery(); captureRequested = true; model.capture() }
            val savedDeadline = SystemClock.elapsedRealtime() + 7000
            while (SystemClock.elapsedRealtime() < savedDeadline) {
                val state = model.state.value
                if (!state.saving && state.lastCapture != null && state.lastCapture != previous) {
                    saved = state.lastCapture
                    break
                }
                SystemClock.sleep(20)
            }
            check(saved != null) { "Capture did not finish while gallery work was blocked: ${model.state.value.captureMessage}" }
            check(releaseGallery.count == 1L && model.state.value.galleryLoading) { "Gallery barrier was released before capture completed" }
            val loaded = CaptureCatalog.load(targetContext, saved!!)
            check(loaded.metadata.getString("source") == "fixture")
            val fixture = targetContext.assets.open("fixture.yuyv").use { it.readBytes() }
            check(loaded.composite.copyOfRange(98304, 196608).contentEquals(fixture.copyOfRange(98304, 196608)))
            releaseGallery.countDown()
            val captureBlocked = CountDownLatch(1)
            captureExecutor.execute { captureBlocked.countDown(); check(releaseCapture.await(25, TimeUnit.SECONDS)) }
            check(captureBlocked.await(5, TimeUnit.SECONDS))
            runOnMainSync {
                check(model.canCapture()) { "Synthetic source was not ready for queued capture" }
                model.capture()
                model.fixture()
            }
            // Let the replacement source run before releasing the request;
            // the pending capture must retain its original session binding.
            val replacementDeadline = SystemClock.elapsedRealtime() + 5000
            while (model.state.value.busy && SystemClock.elapsedRealtime() < replacementDeadline) SystemClock.sleep(20)
            check(!model.state.value.busy && model.state.value.fixture)
            SystemClock.sleep(500)
            releaseCapture.countDown()
            val cancellationDeadline = SystemClock.elapsedRealtime() + 5000
            while (model.state.value.saving && SystemClock.elapsedRealtime() < cancellationDeadline) SystemClock.sleep(20)
            check(!model.state.value.saving && model.state.value.lastCapture == saved &&
                model.state.value.captureMessage.contains("Capture cancelled: source session changed")) {
                "Queued capture was not cancelled after changing source: ${model.state.value.captureMessage}"
            }
        } finally {
            releaseGallery.countDown()
            releaseCapture.countDown()
            runOnMainSync { store.clear() }
            galleryExecutor?.awaitTermination(5, TimeUnit.SECONDS)
            captureExecutor?.awaitTermination(5, TimeUnit.SECONDS)
            usbExecutor?.awaitTermination(5, TimeUnit.SECONDS)
            val lastCreated = if (captureRequested) model.state.value.lastCapture?.takeIf { it != previous } else null
            listOfNotNull(saved, lastCreated).distinctBy { it.id }.forEach { capture ->
                listOf(capture.rendered, capture.raw, capture.metadata).forEach { targetContext.contentResolver.delete(it, null, null) }
            }
            reader.setOnImageAvailableListener(null, null)
            consumer.quitSafely(); consumer.join(2000); reader.close()
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

    private fun validateMeasurementWorkload(): JSONObject {
        val bridge = NativeBridge()
        val engine = bridge.create()
        val consumer = HandlerThread("ThermalWorkloadSurface").apply { start() }
        val reader = ImageReader.newInstance(768, 576, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
        try {
            val frame = targetContext.assets.open("fixture.yuyv").use { it.readBytes() }
            val geometry = IntArray(16 * 6)
            for (i in 0 until 16) {
                val values = if (i % 2 == 0) intArrayOf(i+1, 2, 0, 0, 255, 191)
                    else intArrayOf(i+1, 3, 0, i*12, 255, 191-i*12)
                values.copyInto(geometry, i*6)
            }
            bridge.surface(engine, reader.surface)
            bridge.configure(engine, 2, false, 0, false, false, 20f, 30f)
            bridge.correction(engine, .96, -20.0, true)
            bridge.restoreMeasurements(engine, geometry, 0, 0, 1, 20f, 30f)
            bridge.replay(engine, frame)
            awaitFrame(bridge, engine)
            val deadline = SystemClock.elapsedRealtime() + 70_000
            var captured = false
            var stats = JSONObject(bridge.summary(engine))
            while (stats.getLong("received") < 1500 && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(500)
                stats = JSONObject(bridge.summary(engine))
                check(stats.getLong("overflow") == 0L && stats.getLong("malformed") == 0L && stats.getString("error").isEmpty()) {
                    "Measurement workload pipeline failure: received=${stats.getLong("received")}, rendered=${stats.getLong("rendered")}, overflow=${stats.getLong("overflow")}, malformed=${stats.getLong("malformed")}, error=${stats.getString("error")}, max_swap_ms=${stats.getDouble("max_callback_to_swap_ms")}, processing_ms=${stats.getDouble("callback_processing_ms")}, render_work_ms=${stats.getDouble("render_work_ms")}"
                }
                if (!captured && stats.getLong("received") >= 750) {
                    val packet = bridge.capture(engine)
                    val length = ByteBuffer.wrap(packet, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                    val metadata = JSONObject(String(packet, 4, length, Charsets.UTF_8))
                    check(metadata.getJSONArray("measurements").length() == 16)
                    check(packet.copyOfRange(4+length+98304, 4+length+196608).contentEquals(frame.copyOfRange(98304,196608)))
                    captured = true
                }
            }
            check(stats.getLong("received") >= 1500 && captured) { "Measurement workload did not complete its one-minute interval" }
            bridge.cancel(engine)
            val drain = SystemClock.elapsedRealtime() + 2000
            do {
                stats = JSONObject(bridge.summary(engine))
                if (stats.getLong("received") == stats.getLong("rendered")) break
                SystemClock.sleep(20)
            } while (SystemClock.elapsedRealtime() < drain)
            check(stats.getLong("received") == stats.getLong("rendered")) { "Measurement workload left unrendered accepted frames" }
            check(stats.getDouble("fps") in 24.5..25.5)
            check(stats.getJSONArray("measurements").length() == 16)
            val keys = listOf("received", "rendered", "fps", "malformed", "overflow", "source_sequence_gaps", "callback_to_swap_ms", "max_callback_to_swap_ms", "callback_processing_ms", "render_work_ms", "max_render_work_ms", "presentation_latency_ms", "presentation_samples", "error")
            return JSONObject().apply {
                put("source", "synthetic fixture"); put("surface", "ImageReader 768x576"); put("geometry", "8 full-plane boxes and 8 full-width lines")
                put("emissivity", .96); put("reflected_celsius", -20); put("capture_during_run", captured)
                keys.forEach { put(it, stats.get(it)) }
            }
        } finally {
            bridge.surface(engine, null); bridge.stop(engine); bridge.destroy(engine)
            reader.setOnImageAvailableListener(null, null)
            consumer.quitSafely(); consumer.join(2000); reader.close()
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
                var unchanged = model.state.value.frame
                // Both properties must describe the same telemetry snapshot.
                // A transient delivery delay need not end the bounded wait.
                while ((unchanged.unchangedMs < 700 || unchanged.ageMs >= 300) && SystemClock.elapsedRealtime() < unchangedDeadline) {
                    SystemClock.sleep(20)
                    unchanged = model.state.value.frame
                }
                check(unchanged.unchangedMs >= 700 && unchanged.ageMs < 300) {
                    "Repeated content was not distinguished from stalled transport: unchanged_ms=${unchanged.unchangedMs}, age_ms=${unchanged.ageMs}, received=${unchanged.received}"
                }
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
