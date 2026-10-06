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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

private val Blue = Color(0xff0000a8)
private val Gray = Color(0xffc6c6c6)
private val Light = Color(0xffe8e8e8)
private val Ink = Color(0xff101010)
private val Rule = Color(0xff666666)
private val Sans = FontFamily(Font(R.font.plex_sans_regular), Font(R.font.plex_sans_semibold, FontWeight.SemiBold))
private val Mono = FontFamily(Font(R.font.plex_mono_regular))

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
            ThermalScreen(state, model, ::requestCameraPermission, ::connectNetwork, ::share) {
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
            intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> model.cameraMode()
            BuildConfig.DEBUG && intent.getStringExtra("network_url") != null -> connectNetwork(intent.getStringExtra("network_url")!!)
            BuildConfig.DEBUG && intent.getBooleanExtra("fixture", false) -> model.fixture()
            else -> model.connect()
        }
    }
    private fun requestCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) cameraPermission.launch(Manifest.permission.CAMERA)
        else model.connect()
    }
    private fun connectNetwork(address: String) {
        if (android.os.Build.VERSION.SDK_INT >= 37 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingNetwork = address; networkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else model.network(address)
    }
    override fun onStart() { super.onStart(); model.foreground() }
    override fun onStop() { model.background(); super.onStop() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in heldCaptureKeys) return true
        if (model.canCapture() && keyCode in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_X)) {
            heldCaptureKeys += keyCode
            if (event.repeatCount == 0) model.capture(keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)
            return true
        }
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

@Composable private fun Label(text: String, modifier: Modifier = Modifier, color: Color = Ink, mono: Boolean = false, size: Int = 14) {
    BasicText(text, modifier, style = TextStyle(color = color, fontFamily = if (mono) Mono else Sans, fontSize = size.sp, lineHeight = (size * 1.4).sp))
}

@Composable private fun Action(text: String, selected: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(modifier.defaultMinSize(minHeight = 48.dp).border(1.dp, if (enabled) Blue else Rule)
        .background(if (selected) Blue else Color.White).semantics { stateDescription = if (selected) "Selected" else "Not selected" }
        .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Label(text, color = if (!enabled) Rule else if (selected) Color.White else Blue)
    }
}

@Composable private fun Pane(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, Rule).background(Color.White)) {
        Label(title, Modifier.fillMaxWidth().background(Blue).padding(10.dp), Color.White, mono = true)
        content()
    }
}

private fun temperature(value: Double, fahrenheit: Boolean): String {
    val displayed = if (fahrenheit) value * 9 / 5 + 32 else value
    return if (displayed.isFinite()) String.format(Locale.US, "%.1f %s", displayed, if (fahrenheit) "°F" else "°C") else "—"
}

@Composable private fun ThermalScreen(state: CameraUiState, model: CameraViewModel, permission: () -> Unit, connectNetwork: (String) -> Unit, share: (SavedCapture, String) -> Unit, surfaceCreated: () -> Unit) {
    var sharing by remember { mutableStateOf(false) }
    var sourceDialog by remember { mutableStateOf(false) }
    var spanDialog by remember { mutableStateOf(false) }
    var sourceAddress by remember { mutableStateOf(state.networkUrl) }
    var lowerText by remember { mutableStateOf("") }; var upperText by remember { mutableStateOf("") }
    var spanError by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Gray).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().background(Blue).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("THERMAL FIELD", color = Color.White, mono = true, size = 18)
            Action(if (state.fixture) "Demo" else if (state.network) "Network" else "USB", modifier = Modifier) { sourceDialog = true; model.editing(true) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Pane(if (state.fixture) "DEMO / SYNTHETIC TEMPERATURES" else "RADIOMETRIC VIEW / 256 × 192") {
                Row(Modifier.fillMaxWidth().background(Light).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Rotate +90°", modifier = Modifier.weight(1f)) { model.rotate() }
                    Action("Mirror", state.mirror, modifier = Modifier.weight(1f)) { model.mirror() }
                    Action("Flip 180°", state.flip, modifier = Modifier.weight(1f)) { model.flip() }
                }
                Label("${((state.rotation + if (state.flip) 2 else 0) % 4) * 90}°${if (state.mirror) " · mirrored" else ""}", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), mono = true)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                val ratio = if ((state.rotation + if (state.flip) 2 else 0) % 2 == 0) 4f / 3f else 3f / 4f
                val height = minOf(maxWidth / ratio, 360.dp)
                AndroidView(factory = { context -> SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { model.surface(holder.surface); surfaceCreated() }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { model.surface(holder.surface) }
                        override fun surfaceDestroyed(holder: SurfaceHolder) { model.surface(null) }
                    })
                } }, modifier = Modifier.fillMaxWidth().height(height))
                }
                val visible = state.frame.frame > 0
                Row(Modifier.fillMaxWidth().background(Light).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Label("MIN", color = Color(0xff008ea1), mono = true); Label(if (visible) temperature(state.frame.minimum, state.fahrenheit) else "—", mono = true) }
                    Column { Label("CENTER", mono = true); Label(if (visible) temperature(state.frame.center, state.fahrenheit) else "—", mono = true) }
                    Column { Label("MAX", color = Color(0xffb3261e), mono = true); Label(if (visible) temperature(state.frame.maximum, state.fahrenheit) else "—", mono = true) }
                }
                val lower = if (state.automatic) state.frame.minimum else state.lower.toDouble()
                val upper = if (state.automatic) state.frame.maximum else state.upper.toDouble()
                PaletteScale(state.palette)
                Row(Modifier.fillMaxWidth().background(Light).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label(if (visible || !state.automatic) temperature(lower, state.fahrenheit) else "—", mono = true)
                    Label(if (state.automatic) "AUTO SCALE" else "LOCKED SCALE", mono = true)
                    Label(if (visible || !state.automatic) temperature(upper, state.fahrenheit) else "—", mono = true)
                }
                Label(if (state.fixture) "Synthetic data · not a camera measurement" else "Apparent temperatures · comparison validation pending",
                    Modifier.fillMaxWidth().background(Color(0xfffff4dc)).padding(12.dp))
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
                    Action("Lock current", !state.automatic, enabled = state.frame.frame > 0, modifier = Modifier.weight(1f)) {
                        model.span(false, state.frame.minimum.toFloat(), maxOf(state.frame.minimum.toFloat() + .1f, state.frame.maximum.toFloat()))
                    }
                }
                Action("Set level / span", modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    fun display(value: Float) = if (state.fahrenheit) value * 9 / 5 + 32 else value
                    lowerText = String.format(Locale.US, "%.1f", display(state.lower)); upperText = String.format(Locale.US, "%.1f", display(state.upper))
                    spanError = ""; spanDialog = true; model.editing(true)
                }
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
                    if (BuildConfig.DEBUG) Action("Debug frame dump", enabled = state.frame.frame > 0, modifier = Modifier.weight(1f)) { model.dumpFrame() }
                }
                if (state.captureMessage.isNotEmpty()) Label(state.captureMessage, Modifier.padding(12.dp), mono = true)
                if (state.firmware.isNotEmpty()) Label("Firmware ${state.firmware}", Modifier.padding(12.dp), mono = true)
            }
            Pane("PERFORMANCE / DIAGNOSTIC") {
                val frame = state.frame
                Label(String.format(Locale.US, "%.2f fps · %d received / %d rendered\n%d source gaps · %d malformed · %d overflow\nCallback → swap %.2f ms\n%s", frame.fps, frame.received, frame.rendered, frame.sourceSequenceGaps, frame.malformed, frame.overflow, frame.swapMs,
                    if (frame.presentationSamples > 0) String.format(Locale.US, "Callback → presentation %.2f ms", frame.presentationMs) else "Presentation timestamp unavailable"), Modifier.padding(12.dp), mono = true)
            }
        }
        Column(Modifier.fillMaxWidth().border(1.dp, Rule).background(Gray).padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(if (state.saving) "Saving…" else "Capture", enabled = model.canCapture(), modifier = Modifier.weight(1f)) { model.capture() }
                Action("Raw", enabled = model.canCapture(), modifier = Modifier.weight(1f)) { model.capture(true) }
                Action("Share last", enabled = state.lastCapture != null && !state.saving, modifier = Modifier.weight(1f)) { sharing = true }
            }
            Label("Volume ↓ view · Volume ↑ / X raw", Modifier.padding(top = 4.dp), mono = true, size = 12)
        }
        val status = when {
            state.frame.error.isNotEmpty() -> state.frame.error
            state.busy -> state.status
            state.connected && state.frame.ageMs > 300 -> "Frame delivery stalled"
            state.connected && state.frame.unchangedMs > 300 -> "Live transport · radiometric data unchanged"
            else -> state.status
        }
        Label(status, Modifier.fillMaxWidth().border(1.dp, Rule).background(Light).padding(12.dp), mono = true)
    }
    if (sharing && state.lastCapture != null) Dialog(onDismissRequest = { sharing = false }) {
        Pane("SHARE CAPTURE") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Annotated view, lossless radiometric plane, or the complete three-file capture.")
                Action("Annotated image") { sharing = false; share(state.lastCapture, "image") }
                Action("16-bit radiometric plane") { sharing = false; share(state.lastCapture, "raw") }
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
                Label("Uses raw thermal-field-v1 frames. JPEG video alone cannot supply radiometric measurements.", size = 12)
            }
        }
    }
    if (spanDialog) Dialog(onDismissRequest = { spanDialog = false; model.editing(false) }) {
        Pane("LOCKED LEVEL / SPAN") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Lower ${if (state.fahrenheit) "°F" else "°C"}")
                BasicTextField(lowerText, { lowerText = it }, Modifier.fillMaxWidth().border(1.dp, Rule).padding(12.dp), textStyle = TextStyle(fontFamily = Mono, color = Ink, fontSize = 16.sp))
                Label("Upper ${if (state.fahrenheit) "°F" else "°C"}")
                BasicTextField(upperText, { upperText = it }, Modifier.fillMaxWidth().border(1.dp, Rule).padding(12.dp), textStyle = TextStyle(fontFamily = Mono, color = Ink, fontSize = 16.sp))
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

@Composable private fun PaletteScale(palette: Int) {
    val colors = when (palette) {
        1 -> listOf(Color.Black, Color.White)
        2 -> listOf(Color(0xff000064), Color(0xff005aff), Color(0xff00dcb4), Color(0xffbeff00), Color(0xffff8c00), Color(0xffb40000))
        else -> listOf(Color.Black, Color(0xff2d0050), Color(0xffaa1946), Color(0xfff56e0f), Color(0xffffdc46), Color.White)
    }
    Canvas(Modifier.fillMaxWidth().height(14.dp)) { drawRect(Brush.horizontalGradient(colors)) }
}
