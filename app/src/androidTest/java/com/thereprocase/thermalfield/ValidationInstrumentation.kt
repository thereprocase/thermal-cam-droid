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
    private var layoutRestartPhase = ""
    override fun onCreate(arguments: Bundle?) {
        measurementWorkload = arguments?.getString("workload") == "true"
        layoutRestartPhase = arguments?.getString("layout_restart") ?: ""
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            if (layoutRestartPhase.isNotEmpty()) {
                validateLayoutProcessRestart(layoutRestartPhase)
                result.putString("stream", "Measurement process-restart $layoutRestartPhase passed.\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            validateSavedProvenance()
            validateQueuedControlCancellation()
            validateCaptureDuringGalleryWork()
            validateSavedLiveProfiles()
            validateMeasurementPersistence()
            val workload = if (measurementWorkload) validateMeasurementWorkload().toString() else null
            result.putString("stream", "Native saved provenance/export, queued control cancellation, capture/gallery isolation, saved/live profile isolation and measurement persistence passed.\n" +
                if (workload != null) "Synthetic workload: $workload\n" else "")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "Validation failed: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun validateLayoutProcessRestart(phase: String) {
        check(targetContext.packageName.endsWith(".emulator") && phase in listOf("seed", "verify")) { "Restart phases require the isolated emulator package" }
        val preferences = targetContext.getSharedPreferences("display", android.content.Context.MODE_PRIVATE)
        val evidence = java.io.File(targetContext.cacheDir, "measurement-restart-validation.json")
        if (phase == "seed") {
            check(!evidence.exists()) { "An unfinished restart check already exists" }
            val original = preferences.all["measurementLayout"]
            check(original == null || original is String) { "Restore the invalid preference type before this restart check" }
            evidence.writeText(JSONObject().put("original", original ?: JSONObject.NULL).put("pid", android.os.Process.myPid()).toString())
            check(preferences.edit().remove("measurementLayout").commit())
        }
        val record = JSONObject(evidence.readText())
        val consumer = HandlerThread("ThermalRestartValidationSurface").apply { start() }
        val reader = ImageReader.newInstance(256, 192, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
        val store = ViewModelStore()
        val executors = mutableListOf<ExecutorService>()
        var seeded = false
        try {
            lateinit var model: CameraViewModel
            runOnMainSync {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(targetContext.applicationContext as Application))[CameraViewModel::class.java]
                model.surface(reader.surface); model.fixture(); model.foreground()
            }
            for (name in listOf("worker", "correctionWorker", "captureWorker", "galleryWorker")) executors += CameraViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(model) as ExecutorService
            fun ready(count: Int) {
                val deadline = SystemClock.elapsedRealtime()+8000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (model.canCapture() && model.state.value.frame.measurements.size == count) return
                    SystemClock.sleep(20)
                }
                error("Restart layout did not become ready")
            }
            val bridge = CameraViewModel::class.java.getDeclaredField("bridge").apply { isAccessible = true }.get(model) as NativeBridge
            val engine = CameraViewModel::class.java.getDeclaredField("engine").apply { isAccessible = true }.getLong(model)
            if (phase == "seed") {
                ready(0)
                runOnMainSync {
                    model.measurementTool(1); model.placeMeasurement(.2, .3, .2, .3); model.placeMeasurement(.7, .6, .7, .6)
                    model.measurementTool(2); model.placeMeasurement(.3, .35, .4, .45)
                    model.measurementTool(3); model.placeMeasurement(.1, .2, .8, .7)
                }
                ready(4)
                val ids = model.state.value.frame.measurements.map { it.id }
                runOnMainSync { model.measurementOptions(ids[0], ids[1], 1, 14f, 32f) }
                ready(4)
                val expected = bridge.measurementState(engine)
                check(preferences.getString("measurementLayout", null) == expected)
                evidence.writeText(record.put("expected", expected).toString())
                seeded = true
            } else {
                check(record.getInt("pid") != android.os.Process.myPid()) { "Restart verifier is still in the seed process" }
                ready(4)
                check(bridge.measurementState(engine) == record.getString("expected")) { "Process restart did not restore the complete layout" }
            }
        } finally {
            runOnMainSync { store.clear() }
            executors.forEach { it.awaitTermination(5, TimeUnit.SECONDS) }
            reader.setOnImageAvailableListener(null, null)
            consumer.quitSafely(); consumer.join(2000); reader.close()
            if (phase == "verify" || !seeded) {
                check(preferences.edit().apply {
                    if (record.isNull("original")) remove("measurementLayout") else putString("measurementLayout", record.getString("original"))
                }.commit())
                check(evidence.delete())
            }
        }
    }

    private fun validateSavedLiveProfiles() {
        val preferences = targetContext.getSharedPreferences("display", android.content.Context.MODE_PRIVATE)
        val keys = listOf("palette", "flip", "rotation", "mirror", "automatic", "lower", "upper", "emissivity", "reflectedCelsius", "corrected", "networkUrl", "measurementLayout")
        val originalPreferences = preferences.all.filterKeys { it in keys }
        val consumer = HandlerThread("ThermalProfileValidationSurface").apply { start() }
        val reader = ImageReader.newInstance(256, 192, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
        val store = ViewModelStore()
        val temporary = mutableListOf<SavedCapture>()
        var capturePending = false
        var capturePrevious: SavedCapture? = null
        var model: CameraViewModel? = null
        val executors = mutableListOf<ExecutorService>()
        try {
            lateinit var active: CameraViewModel
            runOnMainSync {
                active = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(targetContext.applicationContext as Application))[CameraViewModel::class.java]
                model = active
                active.surface(reader.surface)
                active.fixture()
                active.foreground()
            }
            for (name in listOf("worker", "correctionWorker", "captureWorker", "galleryWorker")) {
                executors += CameraViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(active) as ExecutorService
            }
            fun awaitReady(predicate: (CameraUiState) -> Boolean = { true }) {
                val deadline = SystemClock.elapsedRealtime() + 8000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (active.canCapture() && predicate(active.state.value)) return
                    SystemClock.sleep(20)
                }
                val state = active.state.value
                error("Profile did not become ready: ${state.status}, epsilon=${state.emissivity}, geometries=${state.frame.measurements.size}, archive=${state.archive}, applying=${state.profileApplying}, ${active.captureRefusalReason()}")
            }
            fun configure(palette: Int, rotation: Int, flip: Boolean, mirror: Boolean, epsilon: Double, reflected: Double, automatic: Boolean, lower: Float, upper: Float) {
                runOnMainSync {
                    check(!active.state.value.profileApplying)
                    active.palette(palette)
                    repeat((rotation-active.state.value.rotation+4)%4) { active.rotate() }
                    if (active.state.value.flip != flip) active.flip()
                    if (active.state.value.mirror != mirror) active.mirror()
                    active.span(automatic, lower, upper)
                    active.correction(epsilon, reflected, true)
                }
                awaitReady { kotlin.math.abs(it.emissivity-epsilon) < 1e-6 && it.corrected }
            }
            fun capture(): SavedCapture {
                val previous = active.state.value.lastCapture
                capturePrevious = previous
                capturePending = true
                runOnMainSync { active.capture() }
                val deadline = SystemClock.elapsedRealtime() + 8000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val state = active.state.value
                    if (!state.saving && state.lastCapture != null && state.lastCapture != previous) {
                        capturePending = false
                        return state.lastCapture.also { temporary += it }
                    }
                    SystemClock.sleep(20)
                }
                error("Profile fixture capture did not finish: ${active.state.value.captureMessage}")
            }
            val native = CameraViewModel::class.java.getDeclaredField("bridge").apply { isAccessible = true }.get(active) as NativeBridge
            val engine = CameraViewModel::class.java.getDeclaredField("engine").apply { isAccessible = true }.getLong(active)
            fun snapshot(): JSONObject {
                val packet = native.snapshot(engine)
                val length = ByteBuffer.wrap(packet, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                return JSONObject(String(packet, 4, length, Charsets.UTF_8))
            }
            fun topology(metadata: JSONObject): String {
                val geometry = metadata.getJSONArray("measurements")
                return (0 until geometry.length()).joinToString(";") { index ->
                    val g = geometry.getJSONObject(index)
                    "${g.getInt("id")}:${g.getString("kind")}:${g.getJSONArray("sensor_start")}:${g.getJSONArray("sensor_end")}"
                }
            }
            awaitReady()
            runOnMainSync { active.deleteMeasurement(0); active.measurementOptions(0, 0, 0) }
            awaitReady { it.frame.measurements.isEmpty() }
            configure(2, 2, false, true, .96, 20.0, true, 20f, 30f)
            val first = capture()
            configure(0, 0, false, false, .8, 40.0, false, 20f, 30f)
            runOnMainSync { active.measurementTool(1); active.placeMeasurement(.3, .4, .3, .4) }
            awaitReady { it.frame.measurements.size == 1 }
            val second = capture()
            configure(1, 1, true, true, .93, -10.0, false, -20f, 60f)
            runOnMainSync {
                active.deleteMeasurement(0)
                active.measurementTool(1)
                active.placeMeasurement(.2, .3, .2, .3)
                active.placeMeasurement(.7, .6, .7, .6)
                active.measurementTool(2)
                active.placeMeasurement(.3, .35, .4, .45)
                active.measurementTool(3)
                active.placeMeasurement(.1, .2, .8, .7)
            }
            awaitReady { it.frame.measurements.size == 4 }
            val ids = active.state.value.frame.measurements.map { it.id }
            runOnMainSync { active.measurementOptions(ids[0], ids[1], 1, 14f, 32f) }
            awaitReady()
            val live = snapshot()
            val livePreferences = preferences.all.filterKeys { it in keys }
            runOnMainSync { active.openSaved(first) }
            awaitReady { it.archive && it.palette == 2 && kotlin.math.abs(it.emissivity-.96) < 1e-6 && it.frame.measurements.isEmpty() }
            configure(0, 3, true, false, .7, 55.0, false, 5f, 45f)
            runOnMainSync {
                active.measurementTool(2)
                active.placeMeasurement(.15, .2, .35, .4)
                active.measurementOptions(0, 0, 3, 10f, 50f)
            }
            awaitReady { it.frame.measurements.size == 1 }
            check(preferences.all.filterKeys { it in keys } == livePreferences) { "Archive edits changed live preferences" }
            runOnMainSync { active.background(); active.foreground() }
            awaitReady { it.archive && kotlin.math.abs(it.emissivity-.7) < 1e-6 && it.palette == 0 }
            runOnMainSync { active.openSaved(second) }
            awaitReady { it.archive && it.frame.measurements.size == 1 && kotlin.math.abs(it.emissivity-.8) < 1e-6 }
            check(preferences.all.filterKeys { it in keys } == livePreferences)
            runOnMainSync { active.fixture() }
            awaitReady { it.fixture && !it.archive && !it.profileApplying && it.frame.measurements.size == 4 }
            val restored = snapshot()
            for (key in listOf("palette", "rotation_degrees", "mirrored", "automatic_span", "correction_applied")) {
                check(restored.get(key) == live.get(key)) { "Live profile field changed: $key" }
            }
            for (key in listOf("emissivity", "reflected_apparent_celsius", "lower_celsius", "upper_celsius")) {
                check(kotlin.math.abs(restored.getDouble(key)-live.getDouble(key)) < 1e-6) { "Live numeric field changed: $key" }
            }
            check(topology(restored) == topology(live)) { "Live sensor geometry was replaced by archive geometry" }
            check(restored.getJSONObject("delta_t").getInt("first") == ids[0] && restored.getJSONObject("delta_t").getInt("second") == ids[1])
            check(restored.getJSONObject("isotherm").toString() == live.getJSONObject("isotherm").toString())
            check(active.state.value.rotation == 1 && active.state.value.flip && active.state.value.mirror)
            check(preferences.all.filterKeys { it in keys } == livePreferences)
            runOnMainSync { active.openSaved(first); active.fixture() }
            awaitReady { it.fixture && !it.archive && !it.profileApplying && it.frame.measurements.size == 4 }
            check(topology(snapshot()) == topology(live)) { "Cancelled saved load changed live geometry" }
            check(kotlin.math.abs(active.state.value.emissivity-.93) < 1e-6)
            MockRadiometricBridge(targetContext.assets.open("fixture.yuyv").use { it.readBytes() }).use { network ->
                runOnMainSync { active.network(network.address) }
                awaitReady { it.network && it.frame.measurements.size == 4 }
                runOnMainSync { active.openSaved(first) }
                awaitReady { it.archive && kotlin.math.abs(it.emissivity-.96) < 1e-6 }
                runOnMainSync { active.network(network.address) }
                awaitReady { it.network && !it.archive && it.frame.measurements.size == 4 }
                check(topology(snapshot()) == topology(live))
                check(kotlin.math.abs(active.state.value.emissivity-.93) < 1e-6)
                runOnMainSync { active.openSaved(second) }
                awaitReady { it.archive && it.frame.measurements.size == 1 }
                runOnMainSync { active.disconnect() }
                val restoredDeadline = SystemClock.elapsedRealtime() + 8000
                while (active.state.value.profileApplying && SystemClock.elapsedRealtime() < restoredDeadline) SystemClock.sleep(20)
                check(!active.state.value.profileApplying && kotlin.math.abs(active.state.value.emissivity-.93) < 1e-6)
                runOnMainSync { active.fixture() }
                awaitReady { it.fixture && it.frame.measurements.size == 4 }
                check(topology(snapshot()) == topology(live))
            }
        } finally {
            runOnMainSync { store.clear() }
            executors.forEach { it.awaitTermination(5, TimeUnit.SECONDS) }
            val late = model?.state?.value?.lastCapture
            if (capturePending && late != null && late != capturePrevious && late !in temporary) temporary += late
            temporary.forEach { capture -> listOf(capture.rendered, capture.raw, capture.metadata).forEach { targetContext.contentResolver.delete(it, null, null) } }
            preferences.edit().apply {
                for (key in keys) {
                    when (val value = originalPreferences[key]) {
                        is Int -> putInt(key, value)
                        is Float -> putFloat(key, value)
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                        else -> remove(key)
                    }
                }
            }.commit()
            reader.setOnImageAvailableListener(null, null)
            consumer.quitSafely(); consumer.join(2000); reader.close()
        }
    }

    private fun validateMeasurementPersistence() {
        val preferences = targetContext.getSharedPreferences("display", android.content.Context.MODE_PRIVATE)
        val original = preferences.all["measurementLayout"]
        val consumer = HandlerThread("ThermalLayoutValidationSurface").apply { start() }
        val reader = ImageReader.newInstance(256, 192, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
        val stores = mutableListOf<ViewModelStore>()
        val executors = mutableMapOf<ViewModelStore, List<ExecutorService>>()
        try {
            check(preferences.edit().remove("measurementLayout").commit())
            fun create(): Pair<ViewModelStore, CameraViewModel> {
                val store = ViewModelStore().also { stores += it }
                lateinit var model: CameraViewModel
                runOnMainSync {
                    model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(targetContext.applicationContext as Application))[CameraViewModel::class.java]
                    model.surface(reader.surface); model.fixture(); model.foreground()
                }
                executors[store] = listOf("worker", "correctionWorker", "captureWorker", "galleryWorker").map {
                    CameraViewModel::class.java.getDeclaredField(it).apply { isAccessible = true }.get(model) as ExecutorService
                }
                return store to model
            }
            fun close(store: ViewModelStore) {
                runOnMainSync { store.clear() }
                executors.remove(store)?.forEach { check(it.awaitTermination(5, TimeUnit.SECONDS)) }
                stores.remove(store)
            }
            fun ready(model: CameraViewModel, count: Int) {
                val deadline = SystemClock.elapsedRealtime()+8000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (model.canCapture() && model.state.value.frame.measurements.size == count) return
                    SystemClock.sleep(20)
                }
                error("Stored layout was not ready: count=${model.state.value.frame.measurements.size}, ${model.captureRefusalReason()}")
            }
            fun layout(model: CameraViewModel): String {
                val bridge = CameraViewModel::class.java.getDeclaredField("bridge").apply { isAccessible = true }.get(model) as NativeBridge
                val engine = CameraViewModel::class.java.getDeclaredField("engine").apply { isAccessible = true }.getLong(model)
                return bridge.measurementState(engine)
            }
            val (firstStore, first) = create()
            ready(first, 0)
            runOnMainSync {
                first.measurementTool(1)
                first.placeMeasurement(.2, .3, .2, .3)
                first.placeMeasurement(.7, .6, .7, .6)
                first.measurementTool(2); first.placeMeasurement(.3, .35, .4, .45)
                first.measurementTool(3); first.placeMeasurement(.1, .2, .8, .7)
                first.measurementTool(1)
                repeat(12) { index -> val x = .05+index*.05; first.placeMeasurement(x, .8, x, .8) }
            }
            ready(first, 16)
            val ids = first.state.value.frame.measurements.map { it.id }
            runOnMainSync { first.measurementOptions(ids[0], ids[1], 2, 14f, 32f) }
            ready(first, 16)
            val full = layout(first)
            check(preferences.getString("measurementLayout", null) == full)
            runOnMainSync { first.deleteMeasurement(ids.last()) }
            ready(first, 15)
            val persisted = layout(first)
            check(MeasurementLayout.decode(persisted).nextId == ids.last()+1)
            close(firstStore)
            val (secondStore, second) = create()
            ready(second, 15)
            check(layout(second) == persisted) { "Fresh engine did not restore sensor geometry/options/ID sequence" }
            check(second.state.value.deltaFirst == ids[0] && second.state.value.deltaSecond == ids[1] && second.state.value.isothermMode == 2)
            runOnMainSync { second.measurementTool(1); second.placeMeasurement(.9, .9, .9, .9) }
            ready(second, 16)
            check(second.state.value.frame.measurements.last().id == ids.last()+1) { "Restart reused the removed measurement's ID" }
            runOnMainSync { second.deleteMeasurement(0) }
            ready(second, 0)
            val cleared = layout(second)
            close(secondStore)
            val (thirdStore, third) = create()
            ready(third, 0)
            check(layout(third) == cleared) { "Cleared layout was not persisted" }
            close(thirdStore)

            val invalid = listOf<(JSONObject) -> Unit>(
                { it.put("version", 2) },
                { it.getJSONArray("geometry").put(it.getJSONArray("geometry").getJSONArray(0)) },
                { it.getJSONArray("geometry").getJSONArray(0).put(0, 1.5) },
                { it.getJSONArray("geometry").getJSONArray(0).put(0, "1") },
                { it.getJSONArray("geometry").getJSONArray(1).put(0, ids[0]) },
                { it.getJSONArray("geometry").getJSONArray(0).put(2, 256) },
                { it.getJSONArray("geometry").getJSONArray(0).put(3, 192) },
                { it.put("next_id", ids[0]) },
                { it.put("isotherm", 4) },
                { it.put("lower", 35).put("upper", 10) },
                { it.put("first", -1) },
            )
            invalid.forEachIndexed { index, change ->
                var rejected = false
                try { MeasurementLayout.decode(JSONObject(full).also(change).toString()) }
                catch (_: Exception) { rejected = true }
                check(rejected) { "Malformed layout case $index was accepted" }
            }
            check(preferences.edit().putString("measurementLayout", JSONObject(full).put("version", 2).toString()).commit())
            val (_, invalidModel) = create()
            ready(invalidModel, 0)
            check(invalidModel.state.value.captureMessage.startsWith("Stored measurements could not be restored:"))
        } finally {
            stores.toList().forEach { store ->
                runOnMainSync { store.clear() }
                executors[store]?.forEach { it.awaitTermination(5, TimeUnit.SECONDS) }
            }
            preferences.edit().apply {
                when (original) {
                    is String -> putString("measurementLayout", original)
                    is Int -> putInt("measurementLayout", original)
                    is Float -> putFloat("measurementLayout", original)
                    is Boolean -> putBoolean("measurementLayout", original)
                    is Long -> putLong("measurementLayout", original)
                    else -> remove("measurementLayout")
                }
            }.commit()
            reader.setOnImageAvailableListener(null, null)
            consumer.quitSafely(); consumer.join(2000); reader.close()
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
                        if (state.network && state.networkUrl == address && state.connected && !state.busy && state.frame.received >= 10 && state.frame.frame > 0 && state.frame.ageMs in 0.0..299.0) return
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
                val previousStreams = first.streams.get()
                first.paused.set(true)
                val recoveryDeadline = SystemClock.elapsedRealtime()+6000
                while (!canRestartStalledSource(model.state.value) && SystemClock.elapsedRealtime() < recoveryDeadline) SystemClock.sleep(20)
                check(canRestartStalledSource(model.state.value)) { "Prolonged synthetic stall did not expose recovery" }
                runOnMainSync { model.restartStalledSource() }
                val reopenDeadline = SystemClock.elapsedRealtime()+6000
                while (first.streams.get() == previousStreams && SystemClock.elapsedRealtime() < reopenDeadline) SystemClock.sleep(20)
                check(first.streams.get() == previousStreams+1) { "Recovery did not reopen the selected bridge exactly once" }
                first.paused.set(false)
                awaitConnected(first.address)
                check(!canRestartStalledSource(model.state.value)) { "Recovery remained eligible after fresh delivery" }
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
