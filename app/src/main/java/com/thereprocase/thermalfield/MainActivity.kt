package com.thereprocase.thermalfield

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.KeyEvent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

internal val Blue = Color(0xff0000a8)
internal val Gray = Color(0xffc6c6c6)
internal val Light = Color(0xffe8e8e8)
internal val Ink = Color(0xff101010)
internal val Rule = Color(0xff666666)
internal val Sans = FontFamily(Font(R.font.plex_sans_regular), Font(R.font.plex_sans_semibold, FontWeight.SemiBold))
internal val Mono = FontFamily(Font(R.font.plex_mono_regular))

class MainActivity : ComponentActivity() {
    private val heldCaptureKeys = mutableSetOf<Int>()
    private lateinit var model: CameraViewModel
    private var requestedFixture = false
    private var requestedNetwork: String? = null
    private var pendingNetwork: String? = null
    private val networkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val address = pendingNetwork; pendingNetwork = null
        if (granted && address != null) model.network(address)
        else model.message("Local network access denied. Allow Nearby devices in app permissions to connect a LAN camera.")
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.cameraPermission(granted, deniedRequest = !granted)
        if (granted) model.connect()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        model = ViewModelProvider(this)[CameraViewModel::class.java]
        requestedFixture = BuildConfig.DEBUG && intent.getBooleanExtra("fixture", false)
        requestedNetwork = if (BuildConfig.DEBUG) intent.getStringExtra("network_url") else null
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.rotationLocked) {
                requestedOrientation = if (state.rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            LaunchedEffect(state.fullScreen) {
                window.insetsController?.let { controller ->
                    controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (state.fullScreen || BuildConfig.DEBUG && intent.getBooleanExtra("screenshots", false)) controller.hide(android.view.WindowInsets.Type.systemBars())
                    else controller.show(android.view.WindowInsets.Type.systemBars())
                }
            }
            BackHandler(enabled = state.fullScreen) { model.fullScreen(false) }
            ThermalScreen(state, model, ::requestCameraPermission, ::openAppSettings, ::connectNetwork, ::share) {
                if (requestedNetwork != null) { val address = requestedNetwork!!; requestedNetwork = null; connectNetwork(address) }
                else if (requestedFixture) { requestedFixture = false; model.fixture() }
            }
        }
        if (requestedNetwork == null && !requestedFixture) requestCameraPermission()
        if (BuildConfig.DEBUG && intent.getBooleanExtra("screenshots", false)) window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        when {
            intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> model.cameraAttached()
            BuildConfig.DEBUG && intent.getStringExtra("network_url") != null -> connectNetwork(intent.getStringExtra("network_url")!!)
            BuildConfig.DEBUG && intent.getBooleanExtra("fixture", false) -> model.fixture()
            else -> model.connect()
        }
    }
    private fun requestCameraPermission() {
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        model.cameraPermission(granted)
        if (!granted) cameraPermission.launch(Manifest.permission.CAMERA)
        else model.connect()
    }
    private fun openAppSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: android.content.ActivityNotFoundException) {
            model.message("App settings could not be opened. Open Android Settings → Apps → Thermal Field → Permissions.")
        }
    }
    private fun connectNetwork(address: String) {
        if (android.os.Build.VERSION.SDK_INT >= 37 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingNetwork = address; networkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else model.network(address)
    }
    override fun onStart() {
        super.onStart()
        model.cameraPermission(checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        model.foreground()
    }
    override fun onStop() { model.background(); super.onStop() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in heldCaptureKeys) return true
        if (model.canCapture() && keyCode in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_X)) {
            heldCaptureKeys += keyCode
            if (event.repeatCount == 0) model.capture(keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)
            return true
        }
        if (event.repeatCount == 0 && keyCode in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) model.message("Not captured · ${model.captureRefusalReason()}")
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (heldCaptureKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }
    private fun share(capture: SavedCapture, kind: String) {
        val uris = when (kind) { "raw" -> listOf(capture.raw); "all" -> listOf(capture.rendered, capture.raw, capture.metadata); else -> listOf(capture.rendered) }
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (kind == "all") "*/*" else "image/png"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newUri(contentResolver, "Thermal capture", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share thermal capture"))
    }
}

@Composable internal fun Label(text: String, modifier: Modifier = Modifier, color: Color = Ink, mono: Boolean = false, size: Int = 14) {
    BasicText(text, modifier, style = TextStyle(color = color, fontFamily = if (mono) Mono else Sans, fontSize = size.sp, lineHeight = (size * 1.4).sp))
}

@Composable internal fun Action(text: String, selected: Boolean? = null, enabled: Boolean = true, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(modifier.defaultMinSize(minHeight = 48.dp).border(1.dp, if (enabled) Blue else Rule)
        .background(if (selected == true) Blue else Color.White).semantics { if (selected != null) stateDescription = if (selected) "Selected" else "Not selected" }
        .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Label(text, color = if (!enabled) Rule else if (selected == true) Color.White else Blue)
    }
}

@Composable private fun TemperatureInput(value: String, change: (String) -> Unit) {
    // Decimal IME hints do not specify a signed-number keypad. Explicit sign
    // control keeps cold-scene inputs accessible across keyboard layouts.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(value, change, Modifier.weight(1f).border(1.dp, Rule).background(Color.White).padding(12.dp),
            textStyle = TextStyle(fontFamily = Mono, color = Ink, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        Action("±", modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = "Change temperature sign" }) {
            change(if (value.startsWith("-")) value.removePrefix("-") else "-${value.removePrefix("+")}")
        }
    }
}

@Composable internal fun Pane(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, Rule).background(Color.White)) {
        Label(title, Modifier.fillMaxWidth().background(Blue).padding(10.dp), Color.White, mono = true)
        content()
    }
}

internal fun temperature(value: Double, fahrenheit: Boolean): String {
    val displayed = if (fahrenheit) value * 9 / 5 + 32 else value
    return if (displayed.isFinite()) String.format(Locale.US, "%.1f %s", displayed, if (fahrenheit) "°F" else "°C") else "—"
}

@Composable private fun ThermalScreen(state: CameraUiState, model: CameraViewModel, permission: () -> Unit, settings: () -> Unit, connectNetwork: (String) -> Unit, share: (SavedCapture, String) -> Unit, surfaceCreated: () -> Unit) {
    var sharing by remember { mutableStateOf(false) }
    var galleryDialog by remember { mutableStateOf(false) }
    var sourceDialog by remember { mutableStateOf(false) }
    var spanDialog by remember { mutableStateOf(false) }
    var sourceAddress by remember { mutableStateOf(state.networkUrl) }
    var lowerText by remember { mutableStateOf("") }; var upperText by remember { mutableStateOf("") }
    var spanError by remember { mutableStateOf("") }
    var correctionDialog by remember { mutableStateOf(false) }
    var emissivityText by remember { mutableStateOf("") }; var reflectedText by remember { mutableStateOf("") }
    var correctionError by remember { mutableStateOf("") }
    var isothermDialog by remember { mutableStateOf(false) }
    var isothermMode by remember { mutableStateOf(1) }
    var isothermLower by remember { mutableStateOf("20") }; var isothermUpper by remember { mutableStateOf("30") }
    var isothermError by remember { mutableStateOf("") }
    var licenseDialog by remember { mutableStateOf(false) }
    var licenseFile by remember { mutableStateOf("") }
    var licenseText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val licenseFiles = remember { context.assets.list("licenses")?.sorted() ?: emptyList() }
    LaunchedEffect(state.profileRevision) {
        // A lasting revision also catches a transition shorter than a Compose
        // frame, so archive dialog inputs do not later apply to the live survey.
        spanDialog = false
        correctionDialog = false
        isothermDialog = false
        model.editing(false)
    }
    LaunchedEffect(licenseDialog, licenseFile) {
        if (licenseDialog && licenseFile in licenseFiles) {
            licenseText = "Loading…"
            licenseText = withContext(Dispatchers.IO) { context.assets.open("licenses/$licenseFile").bufferedReader().use { it.readText() } }
        }
    }
    if (state.fullScreen) { FullScreenView(state, model, surfaceCreated); return }
    val compact = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    Column(Modifier.fillMaxSize().background(Gray).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().background(Blue).padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("THERMAL FIELD", color = Color.White, mono = true, size = 18)
            Action("Full screen") { model.fullScreen(true) }
            Action(if (state.archive) "Saved" else if (state.fixture) "Demo" else if (state.network) "Network" else "USB", modifier = Modifier) { sourceDialog = true; model.editing(true) }
        }
        if (state.cameraPermissionMissing && !state.fixture && !state.archive && !state.network) {
            Row(Modifier.fillMaxWidth().background(Light).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("USB capture needs Camera access. Enable it in Android permissions.", Modifier.weight(1f), size = 12)
                Action("App settings", action = settings)
            }
        }
        val live: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { viewportHeight ->
            Pane(if (state.archive) "SAVED CAPTURE / ORIGINAL FRAME" else if (state.fixture) "DEMO / SYNTHETIC TEMPERATURES" else "RADIOMETRIC VIEW / 256 × 192") {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                val ratio = if ((state.rotation + if (state.flip) 2 else 0) % 2 == 0) 4f / 3f else 3f / 4f
                val height = minOf(maxWidth / ratio, viewportHeight)
                ThermalViewport(state, model, ratio, Modifier.fillMaxWidth().height(height), surfaceCreated)
                }
                val visible = state.frame.frame > 0 && (state.connected || state.fixture || state.archive) && state.frame.error.isEmpty()
                Row(Modifier.fillMaxWidth().background(Light).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (compact) {
                        Label("MIN ${if (visible) temperature(state.frame.minimum, state.fahrenheit) else "—"}", mono = true, size = 12)
                        Label("C ${if (visible) temperature(state.frame.center, state.fahrenheit) else "—"}", mono = true, size = 12)
                        Label("MAX ${if (visible) temperature(state.frame.maximum, state.fahrenheit) else "—"}", mono = true, size = 12)
                    } else {
                        Column { Label("MIN", color = Color(0xff008ea1), mono = true); Label(if (visible) temperature(state.frame.minimum, state.fahrenheit) else "—", mono = true) }
                        Column { Label("CENTER", mono = true); Label(if (visible) temperature(state.frame.center, state.fahrenheit) else "—", mono = true) }
                        Column { Label("MAX", color = Color(0xffb3261e), mono = true); Label(if (visible) temperature(state.frame.maximum, state.fahrenheit) else "—", mono = true) }
                    }
                }
                val lower = if (state.automatic) state.frame.minimum else state.lower.toDouble()
                val upper = if (state.automatic) state.frame.maximum else state.upper.toDouble()
                PaletteScale(state.palette)
                Row(Modifier.fillMaxWidth().background(Light).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label(if (visible || !state.automatic) temperature(lower, state.fahrenheit) else "—", mono = true)
                    Label(if (state.automatic) "AUTO SCALE" else "LOCKED SCALE", mono = true)
                    Label(if (visible || !state.automatic) temperature(upper, state.fahrenheit) else "—", mono = true)
                }
                Label(if (state.fixture || state.archive && state.archiveSynthetic) "Synthetic data · not a camera measurement" else if (state.archive) "Saved frame · original acquisition time · validation pending" else if (state.corrected) "Corrected ε ${String.format(Locale.US, "%.3f", state.emissivity)} · reflected ${temperature(state.reflectedCelsius, state.fahrenheit)} · baseline/accuracy validation pending" else "Apparent temperatures · emissivity not applied · comparison validation pending",
                    Modifier.fillMaxWidth().background(Color(0xfffff4dc)).padding(8.dp), size = 12)
            }
        }
        val controls: @Composable () -> Unit = {
            Pane("ORIENTATION") {
                Row(Modifier.fillMaxWidth().background(Light).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Rotate +90°", modifier = Modifier.weight(1f)) { model.rotate() }
                    Action("Mirror", state.mirror, modifier = Modifier.weight(1f)) { model.mirror() }
                    Action("Flip 180°", state.flip, modifier = Modifier.weight(1f)) { model.flip() }
                }
                Label("${((state.rotation + if (state.flip) 2 else 0) % 4) * 90}°${if (state.mirror) " · mirrored" else ""}", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), mono = true)
            }
            MeasurementsPane(state, model) {
                fun display(value: Float) = if (state.fahrenheit) value * 1.8 + 32 else value.toDouble()
                isothermLower = String.format(Locale.US, "%.1f", display(state.isothermLower)); isothermUpper = String.format(Locale.US, "%.1f", display(state.isothermUpper))
                isothermMode = if (state.isothermMode == 0) 1 else state.isothermMode; isothermError = ""; isothermDialog = true; model.editing(true)
            }
            Pane("DISPLAY") {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Ironbow", "White-hot", "Rainbow").forEachIndexed { index, name -> Action(name, state.palette == index, modifier = Modifier.weight(1f)) { model.palette(index) } }
                }
                Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(if (state.fahrenheit) "°F" else "°C", modifier = Modifier.weight(1f)) { model.units() }
                    Action("Screen rotation lock", state.rotationLocked, modifier = Modifier.weight(1f)) { model.rotationLock() }
                }
                Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Auto span", state.automatic, modifier = Modifier.weight(1f)) { model.span(true, state.lower, state.upper) }
                    Action("Lock current", !state.automatic, enabled = state.frame.frame > 0 && state.frame.minimum.isFinite() && state.frame.maximum.isFinite(), modifier = Modifier.weight(1f)) {
                        model.span(false, state.frame.minimum.toFloat(), maxOf(state.frame.minimum.toFloat() + .1f, state.frame.maximum.toFloat()))
                    }
                }
                Action("Set level / span", modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    fun display(value: Float) = if (state.fahrenheit) value * 9 / 5 + 32 else value
                    lowerText = String.format(Locale.US, "%.1f", display(if (state.automatic && state.frame.minimum.isFinite()) state.frame.minimum.toFloat() else state.lower))
                    upperText = String.format(Locale.US, "%.1f", display(if (state.automatic && state.frame.maximum.isFinite()) state.frame.maximum.toFloat() else state.upper))
                    spanError = ""; spanDialog = true; model.editing(true)
                }
            }
            Pane("RADIOMETRIC CORRECTION") {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Apparent", !state.corrected, enabled = !state.correctionApplying, modifier = Modifier.weight(1f)) { model.correction(state.emissivity, state.reflectedCelsius, false) }
                    Action("Corrected", state.corrected, enabled = !state.correctionApplying, modifier = Modifier.weight(1f)) { model.correction(state.emissivity, state.reflectedCelsius, true) }
                }
                if (state.correctionApplying) Label("Applying correction inputs…", Modifier.padding(12.dp), mono = true)
                if (state.correctionError.isNotEmpty()) Label(state.correctionError, Modifier.padding(12.dp), color = Color(0xffb3261e))
                Label("ε ${String.format(Locale.US, "%.3f", state.emissivity)} · reflected ${temperature(state.reflectedCelsius, state.fahrenheit)}", Modifier.padding(horizontal = 12.dp), mono = true)
                Action("Set emissivity / reflected T", modifier = Modifier.padding(12.dp)) {
                    emissivityText = String.format(Locale.US, "%.3f", state.emissivity)
                    reflectedText = String.format(Locale.US, "%.1f", if (state.fahrenheit) state.reflectedCelsius * 9 / 5 + 32 else state.reflectedCelsius)
                    correctionError = ""; correctionDialog = true; model.editing(true)
                }
                Label("8–14 µm graybody · flat response · short-range transmission assumed 1. Magenta = invalid radiance solution. Reflected temperature is an input, not a sensor reading.", Modifier.padding(12.dp), size = 12)
                if (state.frame.invalidPixels > 0) Label("${state.frame.invalidPixels} invalid pixels excluded from extrema", Modifier.padding(12.dp), color = Color(0xffb3261e))
            }
            Pane("CAMERA") {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(if (state.network) "Reconnect" else "Connect", enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        if (state.network) connectNetwork(state.networkUrl) else { model.cameraMode(); permission() }
                    }
                    Action("Run NUC", enabled = state.connected && !state.busy, modifier = Modifier.weight(1f)) { model.command(true) }
                    Action(if (!state.gainKnown) "Set high gain" else if (state.highGain) "High gain" else "Low gain", enabled = state.connected && !state.busy, modifier = Modifier.weight(1f)) { model.command(false, if (!state.gainKnown) true else !state.highGain) }
                }
                Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Demo", enabled = !state.busy, modifier = Modifier.weight(1f)) { model.fixture() }
                    Action("Captures", modifier = Modifier.weight(1f)) { model.refreshGallery(); galleryDialog = true; model.editing(true) }
                    if (BuildConfig.DEBUG) Action("Debug frame dump", enabled = state.frame.frame > 0, modifier = Modifier.weight(1f)) { model.dumpFrame() }
                }
                if (state.firmware.isNotEmpty()) Label("Firmware ${state.firmware}", Modifier.padding(12.dp), mono = true)
            }
            Pane("PERFORMANCE / DIAGNOSTIC") {
                if (state.archive) Label("Saved-plane repaint metrics · not live acquisition", Modifier.padding(12.dp), size = 12)
                val frame = state.frame
                Label(String.format(Locale.US, "%.2f fps · %d received / %d rendered\n%d source gaps · %d malformed · %d overflow\nCallback → swap %.2f ms\n%s", frame.fps, frame.received, frame.rendered, frame.sourceSequenceGaps, frame.malformed, frame.overflow, frame.swapMs,
                    if (frame.presentationSamples > 0) String.format(Locale.US, "Callback → presentation %.2f ms", frame.presentationMs) else "Presentation timestamp unavailable"), Modifier.padding(12.dp), mono = true)
            }
            Pane("ABOUT / OPEN SOURCE") {
                Label("Thermal Field ${BuildConfig.VERSION_NAME} · development preview", Modifier.padding(12.dp))
                Action("Licenses and notices", modifier = Modifier.padding(12.dp)) { licenseFile = ""; licenseDialog = true; model.editing(true) }
            }
        }
        val profileControls: @Composable () -> Unit = {
            if (state.profileApplying) {
                Pane("SURVEY PROFILE") {
                    Label("Applying survey settings…", Modifier.padding(12.dp), mono = true)
                    if (state.captureMessage.isNotEmpty()) Label(state.captureMessage, Modifier.padding(12.dp))
                }
            } else controls()
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val availableHeight = maxHeight
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(0.56f)) { live(maxOf(40.dp, availableHeight - 165.dp)) }
                    Column(Modifier.weight(0.44f)) {
                        MeasurementToolbar(state, model)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { profileControls() }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MeasurementToolbar(state, model)
                    live(maxOf(100.dp, availableHeight * 0.35f))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { profileControls() }
                }
            }
        }
        Column(Modifier.fillMaxWidth().border(1.dp, Rule).background(Gray).padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(if (state.saving) "Saving…" else "Capture", enabled = model.canCapture(), modifier = Modifier.weight(1f)) { model.capture() }
                Action("Save raw", enabled = model.canCapture(), modifier = Modifier.weight(1f)) { model.capture(true) }
                Action(if (state.lastCapture?.rawPreferred == true) "Share raw…" else "Share…", enabled = state.lastCapture != null && !state.saving, modifier = Modifier.weight(1f)) { sharing = true }
            }
            if (!compact) {
                if (state.captureMessage.isNotEmpty()) Label(state.captureMessage, Modifier.padding(top = 6.dp), mono = true, size = 12)
                Label("Volume ↓ view · ↑ / X plane · both save all three files", Modifier.padding(top = 4.dp), mono = true, size = 12)
            }
        }
        val status = when {
            state.frame.error.isNotEmpty() -> state.frame.error
            state.busy -> state.status
            state.connected && state.frame.ageMs > 300 -> "Frame delivery stalled"
            state.connected && state.frame.unchangedMs > 300 -> "Live transport · radiometric data unchanged"
            else -> state.status
        }
        Label(if (compact && state.captureMessage.isNotEmpty()) "$status · ${state.captureMessage}" else status,
            Modifier.fillMaxWidth().border(1.dp, Rule).background(Light).padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 12.dp), mono = true, size = if (compact) 12 else 14)
    }
    if (galleryDialog) Dialog(onDismissRequest = { galleryDialog = false; model.editing(false) }) {
        Pane("SAVED CAPTURES") {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.galleryLoading) Label("Loading captures…")
                if (state.galleryError.isNotEmpty()) Label(state.galleryError, color = Color(0xffb3261e))
                if (!state.galleryLoading && state.gallery.isEmpty()) Label("No complete captures accessible in Downloads/ThermalField. This gallery lists captures accessible to this installation; importing other files is not implemented yet.")
                state.gallery.forEach { record ->
                    Label("${record.displayTime} · ${if (record.source == "fixture") "SYNTHETIC" else record.source.uppercase(Locale.US)}", mono = true, size = 12)
                    Label(record.summary, size = 12)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("Open / reanalyze", modifier = Modifier.weight(1f)) { galleryDialog = false; model.editing(false); model.openSaved(record.capture) }
                        Action("Share…", modifier = Modifier.weight(1f)) { model.selectCapture(record.capture); galleryDialog = false; model.editing(false); sharing = true }
                    }
                }
                Action("Refresh") { model.refreshGallery() }
                Action("Close") { galleryDialog = false; model.editing(false) }
            }
        }
    }
    if (licenseDialog) Dialog(onDismissRequest = { licenseDialog = false; model.editing(false) }) {
        Pane("LICENSES / NOTICES") {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (licenseFile.isEmpty()) licenseFiles.forEach { filename -> Action(filename) { licenseFile = filename } }
                else { Action("Back to license list") { licenseFile = "" }; Label(licenseText, mono = true, size = 11) }
                Action("Close") { licenseDialog = false; model.editing(false) }
            }
        }
    }
    if (sharing && state.lastCapture != null) Dialog(onDismissRequest = { sharing = false }) {
        Pane("SHARE CAPTURE") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Annotated view, lossless radiometric plane, or the complete three-file capture.")
                val preferred = if (state.lastCapture.rawPreferred) listOf("raw", "image") else listOf("image", "raw")
                preferred.forEachIndexed { index, kind ->
                    Action("${if (kind == "raw") "16-bit radiometric plane" else "Annotated image"}${if (index == 0) " (preferred)" else ""}") { sharing = false; share(state.lastCapture, kind) }
                }
                Action("Image + plane + JSON") { sharing = false; share(state.lastCapture, "all") }
            }
        }
    }
    if (sourceDialog) Dialog(onDismissRequest = { sourceDialog = false; model.editing(false) }) {
        Pane("FRAME SOURCE") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("USB thermal camera") { sourceDialog = false; model.editing(false); model.cameraMode(); permission() }
                Action("Demo · synthetic data") { sourceDialog = false; model.editing(false); model.fixture() }
                Label("Experimental radiometric bridge URL")
                BasicTextField(sourceAddress, { sourceAddress = it }, Modifier.fillMaxWidth().border(1.dp, Rule).background(Color.White).padding(12.dp), textStyle = TextStyle(fontFamily = Mono, color = Ink, fontSize = 13.sp))
                Action("Connect network bridge") { sourceDialog = false; model.editing(false); connectNetwork(sourceAddress) }
                Label("Requires original 256×384 composites and thermal-field-v1 protocol headers. Other thermal camera formats need an adapter.", size = 12)
            }
        }
    }
    if (isothermDialog) Dialog(onDismissRequest = { isothermDialog = false; model.editing(false) }) {
        Pane("ISOTHERM / CURRENT TEMPERATURE MODEL") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Band", "Below", "Above").forEachIndexed { index, label -> Action(label, isothermMode == index+1, modifier = Modifier.weight(1f)) { isothermMode = index+1 } }
                }
                if (isothermMode != 3) {
                    Label("${if (isothermMode == 2) "Below threshold" else "Lower limit"} ${if (state.fahrenheit) "°F" else "°C"}")
                    TemperatureInput(isothermLower) { isothermLower = it }
                }
                if (isothermMode != 2) {
                    Label("${if (isothermMode == 3) "Above threshold" else "Upper limit"} ${if (state.fahrenheit) "°F" else "°C"}")
                    TemperatureInput(isothermUpper) { isothermUpper = it }
                }
                if (isothermError.isNotEmpty()) Label(isothermError, color = Color(0xffb3261e))
                Action("Apply cyan highlight") {
                    val limits = parseIsotherm(isothermMode, isothermLower, isothermUpper, state.fahrenheit)
                    if (limits == null) isothermError = if (isothermMode == 1) "Enter finite limits, upper at least lower." else "Enter a finite threshold."
                    else { model.measurementOptions(mode = isothermMode, lower = limits.lower, upper = limits.upper); isothermDialog = false; model.editing(false) }
                }
                Action("Disable isotherm") { model.measurementOptions(mode = 0); isothermDialog = false; model.editing(false) }
                Label("Thresholds include their endpoints. Invalid correction solutions are excluded. Highlighting does not change the temperature words or span.", size = 12)
            }
        }
    }
    if (correctionDialog) Dialog(onDismissRequest = { correctionDialog = false; model.editing(false) }) {
        Pane("EMISSIVITY / REFLECTED TEMPERATURE") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Emissivity (greater than 0, at most 1)")
                BasicTextField(emissivityText, { emissivityText = it }, Modifier.fillMaxWidth().border(1.dp, Rule).background(Color.White).padding(12.dp), textStyle = TextStyle(fontFamily = Mono, color = Ink, fontSize = 16.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Label("Reflected apparent temperature ${if (state.fahrenheit) "°F" else "°C"}")
                TemperatureInput(reflectedText) { reflectedText = it }
                if (correctionError.isNotEmpty()) Label(correctionError, color = Color(0xffb3261e))
                Action("Apply inputs") {
                    val epsilon = emissivityText.toDoubleOrNull()
                    val reflected = reflectedText.toDoubleOrNull()?.let { if (state.fahrenheit) (it - 32) * 5 / 9 else it }
                    if (epsilon == null || !epsilon.isFinite() || epsilon <= 0 || epsilon > 1 || reflected == null || !reflected.isFinite() || reflected <= -273.15 || reflected > 826.85) correctionError = "Enter valid emissivity and reflected temperature within the model domain (0–1100 K)."
                    else { model.correction(epsilon, reflected, state.corrected); correctionDialog = false; model.editing(false) }
                }
                Label("The default 20 °C reflected input has not been measured. Choose a value appropriate to the scene. Raw mode preserves the camera-apparent temperatures.", size = 12)
            }
        }
    }
    if (spanDialog) Dialog(onDismissRequest = { spanDialog = false; model.editing(false) }) {
        Pane("LOCKED LEVEL / SPAN") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Lower ${if (state.fahrenheit) "°F" else "°C"}")
                TemperatureInput(lowerText) { lowerText = it }
                Label("Upper ${if (state.fahrenheit) "°F" else "°C"}")
                TemperatureInput(upperText) { upperText = it }
                if (spanError.isNotEmpty()) Label(spanError, color = Color(0xffb3261e))
                Action("Apply locked range") {
                    var lower = lowerText.toFloatOrNull(); var upper = upperText.toFloatOrNull()
                    if (lower == null || upper == null || !lower.isFinite() || !upper.isFinite() || upper <= lower) spanError = "Enter finite values with upper greater than lower."
                    else {
                        if (state.fahrenheit) { lower = (lower - 32) * 5 / 9; upper = (upper - 32) * 5 / 9 }
                        model.span(false, lower, upper); spanDialog = false; model.editing(false)
                    }
                }
                Label("Temperatures outside this range clip to the palette endpoints.", size = 12)
            }
        }
    }
}

@Composable internal fun PaletteScale(palette: Int) {
    val colors = when (palette) {
        1 -> listOf(Color.Black, Color.White)
        2 -> listOf(Color(0xff000064), Color(0xff005aff), Color(0xff00dcb4), Color(0xffbeff00), Color(0xffff8c00), Color(0xffb40000))
        else -> listOf(Color.Black, Color(0xff2d0050), Color(0xffaa1946), Color(0xfff56e0f), Color(0xffffdc46), Color.White)
    }
    Canvas(Modifier.fillMaxWidth().height(14.dp)) { drawRect(Brush.horizontalGradient(colors)) }
}
