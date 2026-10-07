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
    val sourceSequenceGaps: Long = 0, val gainReadback: Int = -1, val commandActive: Boolean = false,
    val invalidPixels: Int = 0, val renderRotation: Int = 0, val previewMirrored: Boolean = false,
    val ageMs: Double = -1.0, val unchangedMs: Double = -1.0, val observedAtMillis: Long = 0,
    val swapMs: Double = 0.0, val presentationMs: Double = 0.0,
    val presentationSamples: Long = 0, val error: String = "",
    val emissivity: Double = Double.NaN, val reflectedCelsius: Double = Double.NaN, val corrected: Boolean = false,
    val measurements: List<Measurement> = emptyList(), val delta: Double = Double.NaN,
    val isothermPixels: Int = 0, val measurementVersion: Long = 0, val sourceGeneration: Long = -1,
)

data class CameraUiState(
    val status: String = "Attach camera", val connected: Boolean = false,
    val fixture: Boolean = false, val busy: Boolean = false, val palette: Int = 0,
    val flip: Boolean = false, val fahrenheit: Boolean = false, val rotationLocked: Boolean = false,
    val rotation: Int = 0, val mirror: Boolean = false, val displayRotation: Int = 0,
    val selfie: Boolean = false,
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
    val fullScreen: Boolean = false, val expectedSourceGeneration: Long = -1,
    val archive: Boolean = false, val archiveSynthetic: Boolean = false,
    val profileApplying: Boolean = false,
    val profileRevision: Long = 0,
    val cameraPermissionMissing: Boolean = false,
    internal val nuc: NucFeedback = NucFeedback(),
    val gallery: List<CaptureRecord> = emptyList(), val galleryLoading: Boolean = false, val galleryError: String = "",
)

// Android rotates the whole surface, including its camera pixels. The mounting
// direction determines roll handedness; preview mirroring is applied afterward.
internal val CameraUiState.phoneMounted: Boolean
    get() = !archive && !network && !fixture
internal val CameraUiState.cameraRotation: Int
    get() = (rotation + (if (phoneMounted) displayRotation * (if (selfie) -1 else 1) else 0) + 4) % 4
internal val CameraUiState.renderRotation: Int
    get() = (cameraRotation + (if (flip) 2 else 0)) % 4
internal val CameraUiState.previewMirrored: Boolean
    get() = mirror xor (phoneMounted && selfie)

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val manager = context.getSystemService(UsbManager::class.java)
    private val bridge = NativeBridge()
    private val engine = bridge.create()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "P2UsbCommands") }
    private val captureWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "ThermalCaptureIO") }
    // Listing can read hundreds of sidecars; it must not delay the snapshot
    // taken by a shutter press on the capture executor.
    private val galleryWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "ThermalGalleryIO") }
    private val correctionWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "ThermalCorrection") }
    private val correctionGeneration = AtomicLong(0)
    private val measurementGeneration = AtomicLong(0)
    private val generation = AtomicLong(0)
    private val preferences = context.getSharedPreferences("display", Context.MODE_PRIVATE)
    private val storedMeasurements = runCatching {
        preferences.getString("measurementLayout", null)?.let(MeasurementLayout::decode)
    }
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private var connection: UsbDeviceConnection? = null
    private var activeDevice: String? = null
    @Volatile private var started = false
    @Volatile private var surfaceReady = false
    private var renderSurface: Surface? = null
    @Volatile private var closed = false
    private var fixtureSelected = false
    private var archiveSelected: SavedCapture? = null
    @Volatile private var liveProfileRestorePending = false
    @Volatile private var archiveLoaded: Pair<SavedCapture, LoadedCapture>? = null
    private var networkSelected = false
    @Volatile private var networkFrames: NetworkFrames? = null
    private val pendingCommandSession = AtomicLong(-1)
    private var networkThread: Thread? = null
    private var requestedDevice: String? = null
    private val mutableState = MutableStateFlow(CameraUiState(
        palette = preferences.getInt("palette", 0), flip = preferences.getBoolean("flip", false),
        fahrenheit = preferences.getBoolean("fahrenheit", false),
        rotationLocked = preferences.getBoolean("rotationLocked", false),
        selfie = preferences.getBoolean("selfie", false),
        rotation = preferences.getInt("rotation", 0), mirror = preferences.getBoolean("mirror", false),
        highGain = preferences.getBoolean("usbHighGain", true), gainKnown = false,
        networkUrl = preferences.getString("networkUrl", "") ?: "",
        automatic = preferences.getBoolean("automatic", true), lower = preferences.getFloat("lower", 20f), upper = preferences.getFloat("upper", 30f),
        emissivity = preferences.getFloat("emissivity", 1f).toDouble(), reflectedCelsius = preferences.getFloat("reflectedCelsius", 20f).toDouble(),
        corrected = preferences.getBoolean("corrected", false),
        deltaFirst = storedMeasurements.getOrNull()?.first ?: 0,
        deltaSecond = storedMeasurements.getOrNull()?.second ?: 0,
        isothermMode = storedMeasurements.getOrNull()?.isotherm ?: 0,
        isothermLower = storedMeasurements.getOrNull()?.lower ?: 20f,
        isothermUpper = storedMeasurements.getOrNull()?.upper ?: 30f,
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
                    else mutableState.update { it.copy(status = "USB access denied · tap Connect to ask again", captureMessage = "Allow access in the Android USB dialog to open this camera") }
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
        restoreStoredMeasurements()
        refreshGallery()
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                try {
                    val json = JSONObject(bridge.summary(engine))
                    val frame = FrameTelemetry(
                        frame = json.getLong("frame"), minimum = json.optDouble("minimum", Double.NaN),
                        maximum = json.optDouble("maximum", Double.NaN), center = json.optDouble("center", Double.NaN),
                        invalidPixels = json.optInt("invalid_pixels"), renderRotation = json.optInt("rotation_degrees") / 90,
                        previewMirrored = json.optBoolean("mirrored"),
                        received = json.getLong("received"), rendered = json.getLong("rendered"),
                        malformed = json.getLong("malformed"), overflow = json.getLong("overflow"),
                        sourceSequenceGaps = json.optLong("source_sequence_gaps"), gainReadback = json.getInt("gain_readback"), commandActive = json.getBoolean("command_active"),
                        fps = json.getDouble("fps"), ageMs = json.getDouble("frame_age_ms"),
                        unchangedMs = json.getDouble("unchanged_ms"), swapMs = json.getDouble("callback_to_swap_ms"),
                        presentationMs = json.getDouble("presentation_latency_ms"),
                        presentationSamples = json.getLong("presentation_samples"), error = json.getString("error"),
                        emissivity = json.optDouble("emissivity", Double.NaN), reflectedCelsius = json.optDouble("reflected_apparent_celsius", Double.NaN), corrected = json.optBoolean("correction_applied"),
                        measurements = measurements(json), delta = json.optJSONObject("delta_t")?.optDouble("celsius", Double.NaN) ?: Double.NaN,
                        isothermPixels = json.optJSONObject("isotherm")?.optInt("matched_pixels") ?: 0,
                        measurementVersion = json.optLong("measurement_version"), sourceGeneration = json.getLong("session_generation"), observedAtMillis = android.os.SystemClock.elapsedRealtime(),
                    )
                    mutableState.update { it.copy(frame = frame) }
                } catch (error: Exception) { Log.e("ThermalField", "Telemetry read failed", error) }
                delay(200)
            }
        }
    }

    private fun UsbDevice.isP2Pro() = vendorId == 0x0bda && productId == 0x5830

    private fun nextSession(): Long {
        val token = generation.incrementAndGet()
        mutableState.update { it.copy(nuc = NucFeedback()) }
        return token
    }

    private fun updateSession(token: Long, change: (CameraUiState) -> CameraUiState) {
        // A callback can cross stop/start while native work is in progress.
        // Checking inside the CAS update also rejects a retry after disconnect.
        mutableState.update { if (!closed && started && generation.get() == token) change(it) else it }
    }

    private fun leaveSavedProfile() {
        if (archiveSelected != null) {
            liveProfileRestorePending = true
            mutableState.update { it.copy(profileRevision = it.profileRevision + 1) }
        }
        archiveSelected = null
        archiveLoaded = null
        if (liveProfileRestorePending) mutableState.update { it.copy(profileApplying = true) }
    }

    private fun restoreLiveProfile(token: Long) {
        if (!liveProfileRestorePending) return
        correctionWorker.submit {
            check(!closed && started && generation.get() == token) { "Profile transition cancelled" }
            val profile = JSONObject(bridge.restoreLiveProfile(engine))
            updateSession(token) { it.copy(
                palette = profile.getInt("palette"), flip = profile.getBoolean("flip"),
                // Screen orientation is independent of the persisted manual
                // mounting offset, including after saved-profile recovery.
                rotation = preferences.getInt("rotation", 0), mirror = profile.getBoolean("mirror"),
                automatic = profile.getBoolean("automatic"), lower = profile.getDouble("lower").toFloat(), upper = profile.getDouble("upper").toFloat(),
                emissivity = profile.getDouble("emissivity"), reflectedCelsius = profile.getDouble("reflected"), corrected = profile.getBoolean("corrected"),
                deltaFirst = profile.getInt("delta_first"), deltaSecond = profile.getInt("delta_second"),
                isothermMode = profile.getInt("isotherm_mode"), isothermLower = profile.getDouble("isotherm_lower").toFloat(), isothermUpper = profile.getDouble("isotherm_upper").toFloat(),
                expectedMeasurementVersion = profile.getLong("measurement_version"), measurementTool = 0, selectedMeasurement = 0,
                profileApplying = archiveSelected != null, correctionApplying = false, measurementApplying = false,
                captureMessage = "Returned to live survey settings", correctionError = "",
            ) }
            if (!closed && started && generation.get() == token) { liveProfileRestorePending = false; configure() }
        }.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun foreground() { started = true; when { archiveSelected != null -> openSaved(archiveSelected!!, restoreSettings = false); networkSelected -> network(state.value.networkUrl); fixtureSelected -> fixture(); else -> connect() } }
    fun cameraPermission(granted: Boolean, deniedRequest: Boolean = false) {
        mutableState.update { it.copy(cameraPermissionMissing = !granted,
            captureMessage = when {
                deniedRequest -> "Camera access was not granted. USB capture needs it; retry Connect or enable Camera in app settings."
                granted && it.cameraPermissionMissing -> "Camera access enabled · connect the USB camera"
                else -> it.captureMessage
            }) }
    }
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
            mutableState.update { it.copy(status = "Camera permission is off · retry Connect or open App settings") }; return
        }
        val device = manager.deviceList.values.firstOrNull { it.isP2Pro() }
        if (device == null) { mutableState.update { it.copy(status = "No supported USB camera found · reseat and tap Connect") }; return }
        if (manager.hasPermission(device)) open(device)
        else {
            requestedDevice = device.deviceName
            val token = nextSession()
            mutableState.update { it.copy(status = "Waiting for USB permission") }
            worker.execute {
                try { restoreLiveProfile(token) }
                catch (error: Exception) { updateSession(token) { it.copy(captureMessage = "Live settings could not be restored: ${error.cause?.message ?: error.message}") } }
            }
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
        val token = nextSession()
        activeDevice = device.deviceName
        mutableState.update { it.copy(status = "Opening camera", busy = true, gainKnown = false) }
        worker.execute {
            if (generation.get() != token || !started) return@execute
            try {
                closeConnection()
                restoreLiveProfile(token)
                configure()
                val opened = manager.openDevice(device) ?: error("Android could not open the USB device")
                connection = opened
                val selectedHighGain = preferences.getBoolean("usbHighGain", true)
                val identity = JSONObject(bridge.open(engine, opened.fileDescriptor, selectedHighGain))
                if (generation.get() != token || !started) { closeConnection(); return@execute }
                val sourceGeneration = nativeSourceGeneration()
                updateSession(token) { it.copy(expectedSourceGeneration = sourceGeneration, status = "Live", connected = true, fixture = false, busy = false,
                    serial = identity.optString("serial"), firmware = identity.optString("firmware"), highGain = selectedHighGain, gainKnown = true, network = false) }
            } catch (error: Exception) {
                closeConnection()
                updateSession(token) { it.copy(status = error.message ?: "Camera open failed", busy = false, connected = false) }
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
        if (!keepSource) { fixtureSelected = false; networkSelected = false; leaveSavedProfile() }
        networkFrames?.close()
        val token = nextSession(); requestedDevice = null; activeDevice = null
        bridge.cancel(engine)
        mutableState.update { it.copy(status = message, connected = false, busy = false, fixture = false, network = false, archive = false, gainKnown = false) }
        worker.execute {
            closeConnection()
            try { restoreLiveProfile(token) }
            catch (error: Exception) { updateSession(token) { it.copy(captureMessage = "Live settings could not be restored: ${error.cause?.message ?: error.message}") } }
        }
    }

    fun fixture() {
        leaveSavedProfile()
        fixtureSelected = true
        networkSelected = false
        if (!surfaceReady || !started) return
        val token = nextSession()
        bridge.cancel(engine)
        mutableState.update { it.copy(status = "Fixture replay", fixture = true, connected = false, busy = true, network = false, archive = false, gainKnown = false, serial = "", firmware = "") }
        worker.execute {
            try {
                closeConnection()
                if (generation.get() != token) return@execute
                restoreLiveProfile(token)
                configure()
                bridge.replay(engine, context.assets.open("fixture.yuyv").use { it.readBytes() })
                val sourceGeneration = nativeSourceGeneration()
                updateSession(token) { it.copy(expectedSourceGeneration = sourceGeneration, status = "Fixture replay", busy = false) }
            } catch (error: Exception) { updateSession(token) { it.copy(status = error.message ?: "Fixture failed", busy = false) } }
        }
    }

    fun cameraMode() { disconnect(); connect() }
    fun restartStalledSource() {
        val current = state.value
        if (!canRestartStalledSource(current)) return
        if (current.network) network(current.networkUrl) else cameraMode()
    }
    // Android can deliver both an activity intent and a broadcast for one
    // attach. connect() preserves an existing open or permission request.
    fun cameraAttached() { if (networkSelected || fixtureSelected || archiveSelected != null) cameraMode() else connect() }
    fun network(url: String) {
        val address = url.trim()
        try {
            val uri = java.net.URI(address)
            require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null)
        } catch (_: Exception) { mutableState.update { it.copy(captureMessage = "Enter an HTTP(S) radiometric bridge address") }; return }
        leaveSavedProfile()
        networkSelected = true; fixtureSelected = false
        mutableState.update { it.copy(networkUrl = address) }; preferences.edit().putString("networkUrl", address).apply()
        if (!started || !surfaceReady) return
        val token = nextSession(); bridge.cancel(engine); networkFrames?.close()
        mutableState.update { it.copy(status = "Connecting network stream", connected = false, network = true, fixture = false, archive = false, busy = true, gainKnown = false, serial = "", firmware = "") }
        worker.execute {
            closeConnection()
            if (generation.get() != token || !started) return@execute
            try { restoreLiveProfile(token) }
            catch (error: Exception) {
                updateSession(token) { it.copy(status = "Live settings could not be restored", captureMessage = error.cause?.message ?: error.message.orEmpty(), busy = false) }
                return@execute
            }
            configure()
            bridge.beginNetwork(engine)
            val sourceGeneration = nativeSourceGeneration()
            val active = AtomicBoolean(true)
            val client = NetworkFrames(address, active); networkFrames = client
            networkThread = Thread({
                try {
                    client.frames { bytes, sequence ->
                        if (generation.get() == token && started) {
                            bridge.networkFrame(engine, bytes, sequence)
                            updateSession(token) {
                                val pending = pendingCommandSession.get() == token
                                if (!it.connected || !pending && it.status != "Network live")
                                    it.copy(expectedSourceGeneration = sourceGeneration, status = if (pending) it.status else "Network live", connected = true, network = true, busy = pending)
                                else it
                            }
                        }
                    }
                } catch (error: Exception) {
                    updateSession(token) { it.copy(status = "Network stream stopped: ${error.message ?: "Reconnect"}", connected = false, busy = false) }
                }
            }, "ThermalNetworkFrames").apply { start() }
        }
    }

    fun command(nuc: Boolean, high: Boolean = true) {
        val requested = state.value
        if (!requested.connected || requested.busy) return
        val token = generation.get()
        pendingCommandSession.set(token)
        mutableState.update { it.copy(status = if (nuc) "NUC command pending" else "Changing gain", busy = true,
            nuc = if (nuc) NucFeedback(NucPhase.REQUESTED, android.os.SystemClock.elapsedRealtime()) else it.nuc) }
        worker.execute {
            try {
                if (closed || !started || generation.get() != token) return@execute
                if (requested.network) NetworkFrames.command(requested.networkUrl, if (nuc) "nuc" else "gain", high)
                else if (nuc) bridge.nuc(engine) else bridge.gain(engine, high)
                if (!nuc && !requested.network && !closed && started && generation.get() == token) {
                    if (!preferences.edit().putBoolean("usbHighGain", high).commit())
                        updateSession(token) { it.copy(captureMessage = "Gain changed, but its preference could not be saved") }
                }
                updateSession(token) { it.copy(status = "Live", busy = false,
                    highGain = if (nuc) it.highGain else high, gainKnown = if (nuc) it.gainKnown else true,
                    nuc = if (nuc) NucFeedback(NucPhase.COMPLETED, android.os.SystemClock.elapsedRealtime()) else it.nuc) }
            } catch (error: Exception) {
                updateSession(token) { it.copy(status = error.message ?: "Command failed", busy = false,
                    gainKnown = if (!nuc && !requested.network) false else it.gainKnown,
                    nuc = if (nuc) NucFeedback(NucPhase.FAILED, android.os.SystemClock.elapsedRealtime(), error.message ?: "Command failed") else it.nuc) }
                Log.e("ThermalField", "Camera command failed", error)
            } finally { pendingCommandSession.compareAndSet(token, -1) }
        }
    }

    fun palette(value: Int) {
        if (state.value.profileApplying) return
        mutableState.update { it.copy(palette = value) }
        if (archiveSelected == null) preferences.edit().putInt("palette", value).apply()
        configure()
    }
    fun flip() {
        if (state.value.profileApplying) return
        mutableState.update { it.copy(flip = !it.flip) }
        if (archiveSelected == null) preferences.edit().putBoolean("flip", state.value.flip).apply()
        configure()
    }
    fun units() { mutableState.update { it.copy(fahrenheit = !it.fahrenheit) }; preferences.edit().putBoolean("fahrenheit", state.value.fahrenheit).apply() }
    fun rotationLock() { mutableState.update { it.copy(rotationLocked = !it.rotationLocked) }; preferences.edit().putBoolean("rotationLocked", state.value.rotationLocked).apply() }
    fun rotate() {
        if (state.value.profileApplying) return
        mutableState.update { it.copy(rotation = (it.rotation + 1) % 4) }
        if (archiveSelected == null) preferences.edit().putInt("rotation", state.value.rotation).apply()
        configure()
    }
    fun mounting(selfie: Boolean) {
        if (state.value.profileApplying || !state.value.phoneMounted) return
        mutableState.update { it.copy(selfie = selfie) }
        preferences.edit().putBoolean("selfie", selfie).apply()
        configure()
    }
    fun mirror() {
        if (state.value.profileApplying) return
        mutableState.update { it.copy(mirror = !it.mirror) }
        if (archiveSelected == null) preferences.edit().putBoolean("mirror", state.value.mirror).apply()
        configure()
    }
    fun span(automatic: Boolean, lower: Float, upper: Float) {
        if (state.value.profileApplying) return
        if (!lower.isFinite() || !upper.isFinite() || upper <= lower) return
        mutableState.update { it.copy(automatic = automatic, lower = lower, upper = upper) }
        if (archiveSelected == null) preferences.edit().putBoolean("automatic", automatic).putFloat("lower", lower).putFloat("upper", upper).apply()
        configure()
    }
    fun correction(emissivity: Double, reflectedCelsius: Double, corrected: Boolean) {
        if (state.value.profileApplying) return
        if (!emissivity.isFinite() || emissivity <= 0 || emissivity > 1 || !reflectedCelsius.isFinite() || reflectedCelsius <= -273.15 || reflectedCelsius > 826.85) return
        val token = correctionGeneration.incrementAndGet()
        val persistLive = archiveSelected == null
        mutableState.update { it.copy(correctionApplying = true, correctionError = "") }
        correctionWorker.execute {
            if (closed || token != correctionGeneration.get()) return@execute
            try {
                bridge.correction(engine, emissivity, reflectedCelsius, corrected)
                if (closed || token != correctionGeneration.get()) return@execute
                mutableState.update { it.copy(emissivity = emissivity, reflectedCelsius = reflectedCelsius, corrected = corrected, correctionApplying = false) }
                if (persistLive) preferences.edit().putFloat("emissivity", emissivity.toFloat()).putFloat("reflectedCelsius", reflectedCelsius.toFloat()).putBoolean("corrected", corrected).apply()
            } catch (error: Exception) {
                if (!closed && token == correctionGeneration.get()) mutableState.update { it.copy(correctionApplying = false, correctionError = error.message ?: "Correction could not be applied") }
            }
        }
    }
    fun editing(value: Boolean) { mutableState.update { it.copy(editing = value) } }
    fun refreshGallery() {
        mutableState.update { it.copy(galleryLoading = true, galleryError = "") }
        galleryWorker.execute {
            try {
                val records = CaptureCatalog.list(context)
                mutableState.update { it.copy(gallery = records, galleryLoading = false, lastCapture = it.lastCapture ?: records.firstOrNull()?.capture) }
            } catch (error: Exception) { mutableState.update { it.copy(galleryLoading = false, galleryError = error.message ?: "Captures could not be listed") } }
        }
    }
    fun selectCapture(capture: SavedCapture) { mutableState.update { it.copy(lastCapture = capture) } }
    fun openSaved(capture: SavedCapture, restoreSettings: Boolean = true) {
        if (restoreSettings) {
            archiveLoaded = null
            mutableState.update { it.copy(profileRevision = it.profileRevision + 1) }
        }
        archiveSelected = capture; fixtureSelected = false; networkSelected = false
        if (!started || !surfaceReady) return
        val token = nextSession(); bridge.cancel(engine); networkFrames?.close()
        mutableState.update { it.copy(status = "Loading saved capture", connected = false, fixture = false, network = false, archive = false, busy = true, profileApplying = true) }
        worker.execute {
            try {
                closeConnection()
                restoreLiveProfile(token)
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
                    bridge.beginSavedProfile(engine)
                    bridge.correction(engine, epsilon, reflected, corrected)
                    bridge.configure(engine, palette, false, degrees / 90, mirror, automatic, lower, upper)
                    bridge.restoreMeasurements(engine, packed, first, second, mode, isoLower, isoUpper)
                    val version = bridge.measurementVersion(engine)
                    updateSession(token) { it.copy(emissivity = epsilon, reflectedCelsius = reflected, corrected = corrected, palette = palette, rotation = degrees/90, flip = false, mirror = mirror, automatic = automatic, lower = lower, upper = upper, deltaFirst = first, deltaSecond = second, isothermMode = mode, isothermLower = isoLower, isothermUpper = isoUpper, expectedMeasurementVersion = version, measurementTool = 0, selectedMeasurement = 0) }
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
                val sourceGeneration = nativeSourceGeneration()
                updateSession(token) { it.copy(expectedSourceGeneration = sourceGeneration, status = "Saved capture · original frame", archive = true, archiveSynthetic = original == "fixture", busy = false, profileApplying = false, lastCapture = capture, highGain = gain == 1, gainKnown = gain >= 0) }
            } catch (error: Exception) {
                updateSession(token) { it.copy(status = "Saved capture could not be opened", captureMessage = error.cause?.message ?: error.message ?: "Try another capture", busy = false, archive = false, profileApplying = false) }
            }
        }
    }
    fun measurementTool(kind: Int, id: Int = 0) { if (!state.value.profileApplying) mutableState.update { it.copy(measurementTool = kind, selectedMeasurement = id) } }
    fun displayRotation(quarterTurns: Int) {
        require(quarterTurns in 0..3)
        if (state.value.displayRotation == quarterTurns) return
        mutableState.update { it.copy(displayRotation = quarterTurns) }
        if (!state.value.profileApplying) configure()
    }
    fun fullScreen(value: Boolean) { mutableState.update { it.copy(fullScreen = value) } }
    private fun measurementChange(operation: () -> Unit) {
        if (state.value.profileApplying) return
        val token = measurementGeneration.incrementAndGet()
        val persistLive = archiveSelected == null
        mutableState.update { it.copy(measurementApplying = true) }
        correctionWorker.execute {
            if (closed) return@execute
            try {
                operation()
                if (persistLive) {
                    val layout = bridge.measurementState(engine)
                    MeasurementLayout.decode(layout)
                    check(preferences.edit().putString("measurementLayout", layout).commit()) { "Measurement layout was not saved; changes remain in this session" }
                }
            } catch (error: Exception) { message(error.message ?: "Measurement change failed") }
            if (!closed && token == measurementGeneration.get()) {
                val version = bridge.measurementVersion(engine)
                mutableState.update { it.copy(measurementApplying = false, expectedMeasurementVersion = version) }
            }
        }
    }
    private fun restoreStoredMeasurements() {
        val failure = storedMeasurements.exceptionOrNull()
        if (failure != null) {
            message("Stored measurements could not be restored: ${failure.message ?: "Invalid layout"}")
            return
        }
        val layout = storedMeasurements.getOrNull() ?: return
        val token = measurementGeneration.incrementAndGet()
        mutableState.update { it.copy(measurementApplying = true) }
        correctionWorker.execute {
            if (closed) return@execute
            try {
                layout.restore(bridge, engine)
                mutableState.update { it.copy(deltaFirst = layout.first, deltaSecond = layout.second,
                    isothermMode = layout.isotherm, isothermLower = layout.lower, isothermUpper = layout.upper,
                    expectedMeasurementVersion = bridge.measurementVersion(engine), measurementApplying = token != measurementGeneration.get()) }
            } catch (error: Exception) {
                mutableState.update { it.copy(measurementApplying = token != measurementGeneration.get(), captureMessage = "Stored measurements could not be restored: ${error.message ?: "Invalid layout"}") }
            }
        }
    }
    fun placeMeasurement(x0: Double, y0: Double, x1: Double, y1: Double) {
        val kind = state.value.measurementTool; val id = state.value.selectedMeasurement
        val frame = state.value.frame
        if (kind !in 1..3 || frame.frame == 0L || frame.renderRotation != state.value.renderRotation || frame.previewMirrored != state.value.previewMirrored) return
        measurementChange {
            try {
                bridge.geometry(engine, id, kind, x0, y0, x1, y1, frame.renderRotation, frame.previewMirrored)
                mutableState.update { it.copy(selectedMeasurement = 0, captureMessage = "Measurement placed · sensor coordinates") }
            } catch (error: Exception) { message(error.message ?: "Measurement could not be placed") }
        }
    }
    fun deleteMeasurement(id: Int) {
        if (state.value.profileApplying) return
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
    private fun nativeSourceGeneration(): Long = JSONObject(bridge.summary(engine)).getLong("current_generation")

    private fun configure() {
        state.value.let { bridge.configure(engine, it.palette, it.flip, it.cameraRotation, it.mirror, it.automatic, it.lower, it.upper,
            it.phoneMounted && it.selfie, if (it.phoneMounted) (if (it.selfie) 2 else 1) else 0, it.displayRotation) }
        val version = bridge.measurementVersion(engine)
        mutableState.update { it.copy(expectedMeasurementVersion = version) }
    }

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
            it.profileApplying -> "Survey profile is applying"
            it.expectedSourceGeneration < 0 || it.frame.sourceGeneration != it.expectedSourceGeneration -> "Waiting for this source's first displayed frame"
            it.busy || it.frame.commandActive -> "Camera command in progress"
            !it.fixture && !it.archive && !it.network && (!it.gainKnown || it.frame.gainReadback != if (it.highGain) 1 else 0) -> "Waiting for a frame with confirmed gain"
            it.measurementApplying || it.frame.measurementVersion < it.expectedMeasurementVersion -> "Display or measurements are applying"
            it.correctionApplying || it.frame.corrected != it.corrected || kotlin.math.abs(it.frame.emissivity - it.emissivity) > 1e-7 || kotlin.math.abs(it.frame.reflectedCelsius - it.reflectedCelsius) > 1e-5 -> "Correction inputs are applying"
            it.saving -> "Capture is already saving"
            it.editing -> "Finish editing first"
            it.frame.ageMs !in 0.0..500.0 -> "Frame is stale"
            else -> ""
        }
    }
    fun canCapture(): Boolean = captureRefusalReason().isEmpty()

    private class CaptureSessionChanged : IllegalStateException("Capture cancelled: source session changed")

    private fun checkCaptureSession(session: Long) {
        if (closed || generation.get() != session) throw CaptureSessionChanged()
    }

    fun capture(rawPreferred: Boolean = false) {
        if (!canCapture()) return
        val sourceSession = generation.get()
        val fahrenheit = state.value.fahrenheit
        mutableState.update { it.copy(saving = true, captureMessage = "Saving capture…") }
        captureWorker.execute {
            try {
                checkCaptureSession(sourceSession)
                val packet = bridge.capture(engine)
                // Source teardown may overlap offscreen rendering. Once the
                // snapshot is accepted, later IO can finish independently.
                checkCaptureSession(sourceSession)
                val saved = CaptureStore.save(context, packet, fahrenheit, rawPreferred)
                mutableState.update { it.copy(saving = false, lastCapture = saved,
                    captureMessage = if (rawPreferred) "Saved plane, image + JSON · raw selected for sharing" else "Saved image, plane + JSON · Downloads/ThermalField") }
            } catch (cancelled: CaptureSessionChanged) {
                mutableState.update { it.copy(saving = false, captureMessage = cancelled.message.orEmpty()) }
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
        galleryWorker.shutdown()
        correctionWorker.shutdown()
        super.onCleared()
    }
}
