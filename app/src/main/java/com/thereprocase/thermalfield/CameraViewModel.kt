package com.thereprocase.thermalfield

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.Instant
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class FrameTelemetry(
    val frame: Long = 0, val minimum: Double = 0.0, val maximum: Double = 0.0,
    val center: Double = 0.0, val received: Long = 0, val rendered: Long = 0,
    val malformed: Long = 0, val overflow: Long = 0, val fps: Double = 0.0,
    val sourceSequenceGaps: Long = 0,
    val invalidPixels: Int = 0,
    val ageMs: Double = -1.0, val unchangedMs: Double = -1.0,
    val swapMs: Double = 0.0, val presentationMs: Double = 0.0,
    val presentationSamples: Long = 0, val error: String = "",
    val emissivity: Double = Double.NaN, val reflectedCelsius: Double = Double.NaN, val corrected: Boolean = false,
    val measurements: List<Measurement> = emptyList(), val delta: Double = Double.NaN,
    val isothermPixels: Int = 0, val measurementVersion: Long = 0,
)

data class CameraUiState(
    val status: String = "Attach camera", val connected: Boolean = false,
    val fixture: Boolean = false, val busy: Boolean = false, val palette: Int = 0,
    val flip: Boolean = false, val fahrenheit: Boolean = false, val rotationLocked: Boolean = false,
    val rotation: Int = 0, val mirror: Boolean = false,
    val automatic: Boolean = true, val lower: Float = 20f, val upper: Float = 30f,
    val highGain: Boolean = true, val frame: FrameTelemetry = FrameTelemetry(),
    val serial: String = "", val firmware: String = "", val captureMessage: String = "",
    val saving: Boolean = false, val lastCapture: SavedCapture? = null,
    val network: Boolean = false, val networkUrl: String = "", val gainKnown: Boolean = true,
    val editing: Boolean = false,
    val emissivity: Double = 1.0, val reflectedCelsius: Double = 20.0, val corrected: Boolean = false,
    val correctionApplying: Boolean = false, val correctionError: String = "",
    val measurementTool: Int = 0, val selectedMeasurement: Int = 0,
    val deltaFirst: Int = 0, val deltaSecond: Int = 0,
    val isothermMode: Int = 0, val isothermLower: Float = 20f, val isothermUpper: Float = 30f,
    val measurementApplying: Boolean = false, val expectedMeasurementVersion: Long = 0,
    val fullScreen: Boolean = false,
    val archive: Boolean = false, val archiveSynthetic: Boolean = false,
    val gallery: List<CaptureRecord> = emptyList(), val galleryLoading: Boolean = false, val galleryError: String = "",
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val manager = context.getSystemService(UsbManager::class.java)
    private val bridge = NativeBridge()
    private val engine = bridge.create()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "P2UsbCommands") }
    private val captureWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "ThermalCaptureIO") }
    private val correctionWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "ThermalCorrection") }
    private val correctionGeneration = AtomicLong(0)
    private val measurementGeneration = AtomicLong(0)
    private val generation = AtomicLong(0)
    private val preferences = context.getSharedPreferences("display", Context.MODE_PRIVATE)
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private var connection: UsbDeviceConnection? = null
    private var activeDevice: String? = null
    @Volatile private var started = false
    @Volatile private var surfaceReady = false
    private var renderSurface: Surface? = null
    @Volatile private var closed = false
    private var fixtureSelected = false
    private var archiveSelected: SavedCapture? = null
    @Volatile private var archiveLoaded: Pair<SavedCapture, LoadedCapture>? = null
    private var networkSelected = false
    @Volatile private var networkFrames: NetworkFrames? = null
    @Volatile private var commandPending = false
    private var networkThread: Thread? = null
    private var requestedDevice: String? = null
    private val mutableState = MutableStateFlow(CameraUiState(
        palette = preferences.getInt("palette", 0), flip = preferences.getBoolean("flip", false),
        fahrenheit = preferences.getBoolean("fahrenheit", false),
        rotationLocked = preferences.getBoolean("rotationLocked", false),
        rotation = preferences.getInt("rotation", 0), mirror = preferences.getBoolean("mirror", false),
        networkUrl = preferences.getString("networkUrl", "") ?: "",
        automatic = preferences.getBoolean("automatic", true), lower = preferences.getFloat("lower", 20f), upper = preferences.getFloat("upper", 30f),
        emissivity = preferences.getFloat("emissivity", 1f).toDouble(), reflectedCelsius = preferences.getFloat("reflectedCelsius", 20f).toDouble(),
        corrected = preferences.getBoolean("corrected", false),
    ))
    val state = mutableState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (device?.isP2Pro() == true) cameraAttached()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && !networkSelected && !fixtureSelected && archiveSelected == null && (device.deviceName == activeDevice || device.deviceName == requestedDevice)) disconnect("Camera detached")
                }
                permissionAction -> {
                    if (intent.getLongExtra("generation", -1) != generation.get() || !started) return
                    requestedDevice = null
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) open(device)
                    else mutableState.update { it.copy(status = "USB access denied") }
                }
            }
        }
    }

    init {
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, Context.RECEIVER_NOT_EXPORTED)
        configure()
        state.value.let { correction(it.emissivity, it.reflectedCelsius, it.corrected) }
        refreshGallery()
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                try {
                    val json = JSONObject(bridge.summary(engine))
                    val frame = FrameTelemetry(
                        frame = json.getLong("frame"), minimum = json.optDouble("minimum", Double.NaN),
                        maximum = json.optDouble("maximum", Double.NaN), center = json.optDouble("center", Double.NaN),
                        invalidPixels = json.optInt("invalid_pixels"),
                        received = json.getLong("received"), rendered = json.getLong("rendered"),
                        malformed = json.getLong("malformed"), overflow = json.getLong("overflow"),
                        sourceSequenceGaps = json.optLong("source_sequence_gaps"),
                        fps = json.getDouble("fps"), ageMs = json.getDouble("frame_age_ms"),
                        unchangedMs = json.getDouble("unchanged_ms"), swapMs = json.getDouble("callback_to_swap_ms"),
                        presentationMs = json.getDouble("presentation_latency_ms"),
                        presentationSamples = json.getLong("presentation_samples"), error = json.getString("error"),
                        emissivity = json.optDouble("emissivity", Double.NaN), reflectedCelsius = json.optDouble("reflected_apparent_celsius", Double.NaN), corrected = json.optBoolean("correction_applied"),
                        measurements = measurements(json), delta = json.optJSONObject("delta_t")?.optDouble("celsius", Double.NaN) ?: Double.NaN,
                        isothermPixels = json.optJSONObject("isotherm")?.optInt("matched_pixels") ?: 0,
                        measurementVersion = json.optLong("measurement_version"),
                    )
                    mutableState.update { it.copy(frame = frame) }
                } catch (error: Exception) { Log.e("ThermalField", "Telemetry read failed", error) }
                delay(200)
            }
        }
    }

    private fun UsbDevice.isP2Pro() = vendorId == 0x0bda && productId == 0x5830

    fun foreground() { started = true; when { archiveSelected != null -> openSaved(archiveSelected!!, restoreSettings = false); networkSelected -> network(state.value.networkUrl); fixtureSelected -> fixture(); else -> connect() } }
    fun background() { started = false; disconnect("Capture paused", keepSource = true) }

    fun surface(surface: Surface?) {
        if (closed) return
        renderSurface = surface
        bridge.surface(engine, surface)
        surfaceReady = surface != null
        if (surfaceReady && started && !state.value.connected) {
            when { archiveSelected != null && !state.value.archive -> openSaved(archiveSelected!!, restoreSettings = false); networkSelected && !state.value.network -> network(state.value.networkUrl); fixtureSelected && !state.value.fixture -> fixture(); else -> connect() }
        }
    }
    fun removeSurface(surface: Surface) {
        // A removed portrait view can report destruction after its landscape
        // replacement is attached. Only its own surface may clear the engine.
        if (renderSurface === surface) this.surface(null)
    }
    fun updateSurface(surface: Surface) { if (renderSurface === surface) this.surface(surface) }

    fun connect() {
        if (!started || !surfaceReady || networkSelected || archiveSelected != null || state.value.fixture || state.value.connected || state.value.busy || requestedDevice != null) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            mutableState.update { it.copy(status = "Allow camera access for the USB camera") }; return
        }
        val device = manager.deviceList.values.firstOrNull { it.isP2Pro() }
        if (device == null) { mutableState.update { it.copy(status = "Attach camera") }; return }
        if (manager.hasPermission(device)) open(device)
        else {
            requestedDevice = device.deviceName
            val token = generation.incrementAndGet()
            mutableState.update { it.copy(status = "Waiting for USB permission") }
            // Mutable extras are required for the system's USB result; package
            // scoping prevents this permission token being used by another app.
            val pending = PendingIntent.getBroadcast(context, token.toInt(),
                Intent(permissionAction).setPackage(context.packageName).putExtra("generation", token),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.requestPermission(device, pending)
        }
    }

    private fun open(device: UsbDevice) {
        if (!started || !surfaceReady) return
        val token = generation.incrementAndGet()
        activeDevice = device.deviceName
        mutableState.update { it.copy(status = "Opening camera", busy = true) }
        worker.execute {
            if (generation.get() != token || !started) return@execute
            try {
                closeConnection()
                val opened = manager.openDevice(device) ?: error("Android could not open the USB device")
                connection = opened
                val identity = JSONObject(bridge.open(engine, opened.fileDescriptor))
                if (generation.get() != token || !started) { closeConnection(); return@execute }
                mutableState.update { it.copy(status = "Live", connected = true, fixture = false, busy = false,
                    serial = identity.optString("serial"), firmware = identity.optString("firmware"), highGain = true, gainKnown = true, network = false) }
            } catch (error: Exception) {
                closeConnection()
                if (generation.get() == token) mutableState.update { it.copy(status = error.message ?: "Camera open failed", busy = false, connected = false) }
                Log.e("ThermalField", "Camera open failed", error)
            }
        }
    }

    private fun closeConnection() {
        networkFrames?.close(); networkFrames = null
        networkThread?.join(3000); networkThread = null
        // UsbDeviceConnection owns the original descriptor. Native teardown
        // drains its users before Java is allowed to close that descriptor.
        bridge.stop(engine)
        connection?.close(); connection = null
    }

    fun disconnect(message: String = "Disconnected", keepSource: Boolean = false) {
        if (!keepSource) { fixtureSelected = false; networkSelected = false; archiveSelected = null }
        networkFrames?.close()
        generation.incrementAndGet(); requestedDevice = null; activeDevice = null
        bridge.cancel(engine)
        mutableState.update { it.copy(status = message, connected = false, busy = false, fixture = false, network = false, archive = false) }
        worker.execute { closeConnection() }
    }

    fun fixture() {
        archiveSelected = null
        fixtureSelected = true
        networkSelected = false
        if (!surfaceReady || !started) return
        val token = generation.incrementAndGet()
        bridge.cancel(engine)
        mutableState.update { it.copy(status = "Fixture replay", fixture = true, connected = false, busy = true, archive = false, serial = "", firmware = "") }
        worker.execute {
            try {
                closeConnection()
                if (generation.get() != token) return@execute
                bridge.replay(engine, context.assets.open("fixture.yuyv").use { it.readBytes() })
                mutableState.update { it.copy(status = "Fixture replay", busy = false) }
            } catch (error: Exception) { mutableState.update { it.copy(status = error.message ?: "Fixture failed", busy = false) } }
        }
    }

    fun cameraMode() { disconnect(); connect() }
    // Android can deliver both an activity intent and a broadcast for one
    // attach. connect() preserves an existing open or permission request.
    fun cameraAttached() { if (networkSelected || fixtureSelected || archiveSelected != null) cameraMode() else connect() }
    fun network(url: String) {
        val address = url.trim()
        try {
            val uri = java.net.URI(address)
            require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null)
        } catch (_: Exception) { mutableState.update { it.copy(captureMessage = "Enter an HTTP(S) radiometric bridge address") }; return }
        networkSelected = true; fixtureSelected = false; archiveSelected = null
        mutableState.update { it.copy(networkUrl = address) }; preferences.edit().putString("networkUrl", address).apply()
        if (!started || !surfaceReady) return
        val token = generation.incrementAndGet(); bridge.cancel(engine); networkFrames?.close()
        mutableState.update { it.copy(status = "Connecting network stream", connected = false, network = true, fixture = false, archive = false, busy = true, gainKnown = false, serial = "", firmware = "") }
        worker.execute {
            closeConnection()
            if (generation.get() != token || !started) return@execute
            bridge.beginNetwork(engine)
            val active = AtomicBoolean(true)
            val client = NetworkFrames(address, active); networkFrames = client
            networkThread = Thread({
                try {
                    client.frames { bytes, sequence ->
                        if (generation.get() == token && started) {
                            bridge.networkFrame(engine, bytes, sequence)
                            if (!state.value.connected || !commandPending && state.value.status != "Network live")
                                mutableState.update { it.copy(status = if (commandPending) it.status else "Network live", connected = true, network = true, busy = commandPending) }
                        }
                    }
                } catch (error: Exception) {
                    if (generation.get() == token && started) mutableState.update { it.copy(status = "Network stream stopped: ${error.message ?: "Reconnect"}", connected = false, busy = false) }
                }
            }, "ThermalNetworkFrames").apply { start() }
        }
    }

    fun command(nuc: Boolean, high: Boolean = true) {
        if (!state.value.connected || state.value.busy) return
        val token = generation.get()
        commandPending = true
        mutableState.update { it.copy(status = if (nuc) "Calibration command" else "Changing gain", busy = true) }
        worker.execute {
            try {
                if (state.value.network) NetworkFrames.command(state.value.networkUrl, if (nuc) "nuc" else "gain", high)
                else if (nuc) bridge.nuc(engine) else bridge.gain(engine, high)
                if (generation.get() == token) mutableState.update { it.copy(status = "Live", busy = false,
                    highGain = if (nuc) it.highGain else high, gainKnown = if (nuc) it.gainKnown else true) }
            } catch (error: Exception) {
                if (generation.get() == token) mutableState.update { it.copy(status = error.message ?: "Command failed", busy = false) }
                Log.e("ThermalField", "Camera command failed", error)
            } finally { commandPending = false }
        }
    }

    fun palette(value: Int) { mutableState.update { it.copy(palette = value) }; preferences.edit().putInt("palette", value).apply(); configure() }
    fun flip() { mutableState.update { it.copy(flip = !it.flip) }; preferences.edit().putBoolean("flip", state.value.flip).apply(); configure() }
    fun units() { mutableState.update { it.copy(fahrenheit = !it.fahrenheit) }; preferences.edit().putBoolean("fahrenheit", state.value.fahrenheit).apply() }
    fun rotationLock() { mutableState.update { it.copy(rotationLocked = !it.rotationLocked) }; preferences.edit().putBoolean("rotationLocked", state.value.rotationLocked).apply() }
    fun rotate() { mutableState.update { it.copy(rotation = (it.rotation + 1) % 4) }; preferences.edit().putInt("rotation", state.value.rotation).apply(); configure() }
    fun mirror() { mutableState.update { it.copy(mirror = !it.mirror) }; preferences.edit().putBoolean("mirror", state.value.mirror).apply(); configure() }
    fun span(automatic: Boolean, lower: Float, upper: Float) {
        if (!lower.isFinite() || !upper.isFinite() || upper <= lower) return
        mutableState.update { it.copy(automatic = automatic, lower = lower, upper = upper) }
        preferences.edit().putBoolean("automatic", automatic).putFloat("lower", lower).putFloat("upper", upper).apply(); configure()
    }
    fun correction(emissivity: Double, reflectedCelsius: Double, corrected: Boolean) {
        if (!emissivity.isFinite() || emissivity <= 0 || emissivity > 1 || !reflectedCelsius.isFinite() || reflectedCelsius <= -273.15 || reflectedCelsius > 826.85) return
        val token = correctionGeneration.incrementAndGet()
        mutableState.update { it.copy(correctionApplying = true, correctionError = "") }
        correctionWorker.execute {
            if (closed || token != correctionGeneration.get()) return@execute
            try {
                bridge.correction(engine, emissivity, reflectedCelsius, corrected)
                if (closed || token != correctionGeneration.get()) return@execute
                mutableState.update { it.copy(emissivity = emissivity, reflectedCelsius = reflectedCelsius, corrected = corrected, correctionApplying = false) }
                preferences.edit().putFloat("emissivity", emissivity.toFloat()).putFloat("reflectedCelsius", reflectedCelsius.toFloat()).putBoolean("corrected", corrected).apply()
            } catch (error: Exception) {
                if (!closed && token == correctionGeneration.get()) mutableState.update { it.copy(correctionApplying = false, correctionError = error.message ?: "Correction could not be applied") }
            }
        }
    }
    fun editing(value: Boolean) { mutableState.update { it.copy(editing = value) } }
    fun refreshGallery() {
        mutableState.update { it.copy(galleryLoading = true, galleryError = "") }
        captureWorker.execute {
            try {
                val records = CaptureCatalog.list(context)
                mutableState.update { it.copy(gallery = records, galleryLoading = false, lastCapture = it.lastCapture ?: records.firstOrNull()?.capture) }
            } catch (error: Exception) { mutableState.update { it.copy(galleryLoading = false, galleryError = error.message ?: "Captures could not be listed") } }
        }
    }
    fun selectCapture(capture: SavedCapture) { mutableState.update { it.copy(lastCapture = capture) } }
    fun openSaved(capture: SavedCapture, restoreSettings: Boolean = true) {
        archiveSelected = capture; fixtureSelected = false; networkSelected = false
        if (!started || !surfaceReady) return
        val token = generation.incrementAndGet(); bridge.cancel(engine); networkFrames?.close()
        mutableState.update { it.copy(status = "Loading saved capture", connected = false, fixture = false, network = false, archive = false, busy = true) }
        worker.execute {
            try {
                closeConnection()
                val cached = archiveLoaded?.takeIf { it.first == capture }?.second
                val loaded = if (!restoreSettings && cached != null) cached else CaptureCatalog.load(context, capture)
                val m = loaded.metadata
                val epsilon = m.getDouble("emissivity"); val reflected = m.getDouble("reflected_apparent_celsius")
                val corrected = m.getBoolean("correction_applied"); val automatic = m.getBoolean("automatic_span")
                var lower = m.optDouble("lower_celsius", Double.NaN).toFloat(); var upper = m.optDouble("upper_celsius", Double.NaN).toFloat()
                if (automatic && (!lower.isFinite() || !upper.isFinite())) { lower = 20f; upper = 30f }
                if (automatic && upper <= lower) upper = lower + .1f
                require(epsilon.isFinite() && epsilon > 0 && epsilon <= 1 && reflected.isFinite() && reflected > -273.15 && reflected <= 826.85 && lower.isFinite() && upper.isFinite() && upper > lower) { "Saved correction/span inputs are invalid" }
                val palette = when (m.getString("palette")) { "ironbow" -> 0; "white_hot" -> 1; "rainbow" -> 2; else -> error("Unsupported saved palette") }
                val degrees = m.getInt("rotation_degrees"); require(degrees in listOf(0,90,180,270))
                val mirror = m.getBoolean("mirrored")
                val geometry = m.optJSONArray("measurements"); require((geometry?.length() ?: 0) <= 16)
                val packed = IntArray((geometry?.length() ?: 0) * 6)
                for (i in 0 until (geometry?.length() ?: 0)) {
                    val g = geometry!!.getJSONObject(i); val a = g.getJSONArray("sensor_start"); val b = g.getJSONArray("sensor_end")
                    val values = intArrayOf(g.getInt("id"), when (g.getString("kind")) { "spot" -> 1; "box" -> 2; "line" -> 3; else -> error("Unsupported saved measurement") }, a.getInt(0), a.getInt(1), b.getInt(0), b.getInt(1))
                    values.copyInto(packed, i * 6)
                }
                val delta = m.optJSONObject("delta_t"); val first = delta?.optInt("first") ?: 0; val second = delta?.optInt("second") ?: 0
                val iso = m.optJSONObject("isotherm")
                val mode = if (iso?.optBoolean("enabled") == true) when (iso.optString("mode")) { "band" -> 1; "below" -> 2; "above" -> 3; else -> error("Unsupported saved isotherm") } else 0
                val isoLower = iso?.optDouble("lower_celsius", 20.0)?.toFloat() ?: 20f; val isoUpper = iso?.optDouble("upper_celsius", 30.0)?.toFloat() ?: 30f
                val original = if (m.optString("source") == "archive") m.optJSONObject("identity")?.optString("original_source_kind", "unknown") ?: "unknown" else m.optString("source", "unknown")
                require(original in listOf("camera", "network", "fixture", "unknown")) { "Unsupported saved source" }
                val gain = when (m.optString("gain_mode")) { "high" -> 1; "low" -> 0; else -> -1 }
                if (generation.get() != token || !started) return@execute
                if (restoreSettings || cached == null) correctionWorker.submit {
                    if (generation.get() != token || !started) error("Saved capture load cancelled")
                    bridge.correction(engine, epsilon, reflected, corrected)
                    bridge.configure(engine, palette, false, degrees / 90, mirror, automatic, lower, upper)
                    bridge.restoreMeasurements(engine, packed, first, second, mode, isoLower, isoUpper)
                    val version = bridge.measurementVersion(engine)
                    mutableState.update { it.copy(emissivity = epsilon, reflectedCelsius = reflected, corrected = corrected, palette = palette, rotation = degrees/90, flip = false, mirror = mirror, automatic = automatic, lower = lower, upper = upper, deltaFirst = first, deltaSecond = second, isothermMode = mode, isothermLower = isoLower, isothermUpper = isoUpper, expectedMeasurementVersion = version, measurementTool = 0, selectedMeasurement = 0) }
                }.get(10, java.util.concurrent.TimeUnit.SECONDS)
                if (generation.get() != token || !started) return@execute
                val identity = m.optJSONObject("identity")
                val deviceContext = if (m.optString("source") == "archive") identity?.optJSONObject("original_device_context") else identity
                fun properties(name: String): IntArray {
                    val values = deviceContext?.optJSONArray(name) ?: return IntArray(0)
                    require(values.length() == 6) { "Invalid saved register count" }
                    return IntArray(6) { values.getInt(it).also { value -> require(value in 0..65535) { "Invalid saved register value" } } }
                }
                bridge.archive(engine, loaded.composite, m.getLong("timestamp_unix_ns"), original, gain,
                    deviceContext?.optString("firmware", "") ?: "", properties("original_properties"), properties("configured_properties"))
                archiveLoaded = capture to loaded
                mutableState.update { it.copy(status = "Saved capture · original frame", archive = true, archiveSynthetic = original == "fixture", busy = false, lastCapture = capture, highGain = gain == 1, gainKnown = gain >= 0) }
            } catch (error: Exception) {
                if (generation.get() == token) mutableState.update { it.copy(status = "Saved capture could not be opened", captureMessage = error.cause?.message ?: error.message ?: "Try another capture", busy = false, archive = false) }
            }
        }
    }
    fun measurementTool(kind: Int, id: Int = 0) { mutableState.update { it.copy(measurementTool = kind, selectedMeasurement = id) } }
    fun fullScreen(value: Boolean) { mutableState.update { it.copy(fullScreen = value) } }
    private fun measurementChange(operation: () -> Unit) {
        val token = measurementGeneration.incrementAndGet()
        mutableState.update { it.copy(measurementApplying = true) }
        correctionWorker.execute {
            if (closed) return@execute
            try { operation() } catch (error: Exception) { message(error.message ?: "Measurement change failed") }
            if (!closed && token == measurementGeneration.get()) {
                val version = bridge.measurementVersion(engine)
                mutableState.update { it.copy(measurementApplying = false, expectedMeasurementVersion = version) }
            }
        }
    }
    fun placeMeasurement(x0: Double, y0: Double, x1: Double, y1: Double) {
        val kind = state.value.measurementTool; val id = state.value.selectedMeasurement
        if (kind !in 1..3 || state.value.frame.frame == 0L) return
        measurementChange {
            try {
                bridge.geometry(engine, id, kind, x0, y0, x1, y1)
                mutableState.update { it.copy(selectedMeasurement = 0, captureMessage = "Measurement placed · sensor coordinates") }
            } catch (error: Exception) { message(error.message ?: "Measurement could not be placed") }
        }
    }
    fun deleteMeasurement(id: Int) {
        measurementChange {
            try { bridge.eraseGeometry(engine, id) } catch (error: Exception) { message(error.message ?: "Measurement could not be removed") }
        }
        mutableState.update { it.copy(selectedMeasurement = 0, deltaFirst = if (id == 0 || it.deltaFirst == id) 0 else it.deltaFirst, deltaSecond = if (id == 0 || it.deltaSecond == id) 0 else it.deltaSecond) }
    }
    fun measurementOptions(first: Int = state.value.deltaFirst, second: Int = state.value.deltaSecond, mode: Int = state.value.isothermMode, lower: Float = state.value.isothermLower, upper: Float = state.value.isothermUpper) {
        if (mode !in 0..3 || !lower.isFinite() || !upper.isFinite() || upper < lower) return
        measurementChange {
            try {
                bridge.measurementOptions(engine, first, second, mode, lower, upper)
                mutableState.update { it.copy(deltaFirst = first, deltaSecond = second, isothermMode = mode, isothermLower = lower, isothermUpper = upper) }
            } catch (error: Exception) { message(error.message ?: "Measurement settings could not be applied") }
        }
    }
    fun message(value: String) { mutableState.update { it.copy(captureMessage = value) } }
    private fun configure() { state.value.let { bridge.configure(engine, it.palette, it.flip, it.rotation, it.mirror, it.automatic, it.lower, it.upper) } }

    fun dumpFrame() {
        if (!BuildConfig.DEBUG) return
        worker.execute {
            try {
                val snapshot = bridge.snapshot(engine)
                val length = ByteBuffer.wrap(snapshot, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                check(length > 0 && snapshot.size == length + 4 + 196608) { "Malformed frame snapshot" }
                val metadata = JSONObject(String(snapshot, 4, length, Charsets.UTF_8))
                val bytes = snapshot.copyOfRange(4 + length, snapshot.size)
                val folder = File(context.getExternalFilesDir(null), "raw-frames").apply { mkdirs() }
                val stem = Instant.now().toString().replace(":", "-")
                File(folder, "$stem.yuyv").writeBytes(bytes)
                metadata.put("app_version", BuildConfig.VERSION_NAME)
                File(folder, "$stem.json").writeText(metadata.toString(2))
                mutableState.update { it.copy(captureMessage = "Raw frame saved · ${bytes.size} bytes") }
            } catch (error: Exception) { mutableState.update { it.copy(captureMessage = error.message ?: "Raw dump failed") } }
        }
    }

    fun captureRefusalReason(): String = state.value.let {
        when {
            it.frame.frame == 0L -> "No displayed frame yet"
            !it.connected && !it.fixture && !it.archive -> "Source disconnected"
            it.frame.error.isNotEmpty() -> "Display error"
            it.busy -> "Camera command in progress"
            it.measurementApplying || it.frame.measurementVersion < it.expectedMeasurementVersion -> "Measurements are applying"
            it.correctionApplying || it.frame.corrected != it.corrected || kotlin.math.abs(it.frame.emissivity - it.emissivity) > 1e-7 || kotlin.math.abs(it.frame.reflectedCelsius - it.reflectedCelsius) > 1e-5 -> "Correction inputs are applying"
            it.saving -> "Capture is already saving"
            it.editing -> "Finish editing first"
            it.frame.ageMs !in 0.0..500.0 -> "Frame is stale"
            else -> ""
        }
    }
    fun canCapture(): Boolean = captureRefusalReason().isEmpty()

    fun capture(rawPreferred: Boolean = false) {
        if (!canCapture()) return
        val fahrenheit = state.value.fahrenheit
        mutableState.update { it.copy(saving = true, captureMessage = "Saving capture…") }
        captureWorker.execute {
            try {
                val saved = CaptureStore.save(context, bridge.capture(engine), fahrenheit, rawPreferred)
                mutableState.update { it.copy(saving = false, lastCapture = saved,
                    captureMessage = if (rawPreferred) "Saved plane, image + JSON · raw selected for sharing" else "Saved image, plane + JSON · Downloads/ThermalField") }
            } catch (error: Exception) {
                mutableState.update { it.copy(saving = false, captureMessage = "Capture failed: ${error.message ?: "Try again"}") }
                Log.e("ThermalField", "Capture failed", error)
            }
        }
    }

    override fun onCleared() {
        closed = true
        started = false; generation.incrementAndGet(); context.unregisterReceiver(receiver); bridge.cancel(engine)
        worker.execute { closeConnection(); bridge.destroy(engine) }
        worker.shutdown()
        captureWorker.shutdown()
        correctionWorker.shutdown()
        super.onCleared()
    }
}
