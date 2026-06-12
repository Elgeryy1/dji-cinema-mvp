package com.cinemaapp.djimvp.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.cinemaapp.djimvp.MainActivity
import com.cinemaapp.djimvp.MainViewModel
import com.cinemaapp.djimvp.state.AppUiState
import kotlinx.coroutines.delay

private val AppBg = Color(0xFF050607)
private val Panel = Color(0xEE101418)
private val Stroke = Color(0xFF2B343A)
private val TextPrimary = Color(0xFFF4F7F8)
private val TextMuted = Color(0xFFA0AAB0)
private val RecRed = Color(0xFFE43D36)
private val Accent = Color(0xFF7DD3C7)

private enum class MenuPanel { None, Resolution, Fps, Codec, Look, Gimbal, Settings, Debug }

@Composable
fun CineCamScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val permissions = runtimePermissions()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> viewModel.setPermissionsGranted(result.values.all { it }) }

    DisposableEffect(Unit) {
        val activity = context as? MainActivity
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose { activity?.requestedOrientation = previous }
    }

    LaunchedEffect(Unit) {
        val granted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PermissionChecker.PERMISSION_GRANTED
        }
        viewModel.setPermissionsGranted(granted)
        if (!granted) permissionLauncher.launch(permissions)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = AppBg) {
        if (isLandscape) {
            LandscapeCameraUi(uiState, viewModel, permissionLauncher::launch)
        } else {
            PortraitCameraUi(uiState, viewModel, permissionLauncher::launch)
        }
    }
}

@Composable
private fun LandscapeCameraUi(
    uiState: AppUiState,
    viewModel: MainViewModel,
    requestPermissions: (Array<String>) -> Unit
) {
    CameraStage(uiState, viewModel) {
        TopHud(uiState, Modifier.align(Alignment.TopCenter).padding(12.dp))
        RightRail(
            uiState = uiState,
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(12.dp)
        )
        BottomStrip(
            uiState = uiState,
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
        )
    }
}

@Composable
private fun PortraitCameraUi(
    uiState: AppUiState,
    viewModel: MainViewModel,
    requestPermissions: (Array<String>) -> Unit
) {
    CameraStage(uiState, viewModel) {
        TopHud(uiState, Modifier.align(Alignment.TopCenter).padding(12.dp))
        BottomStrip(
            uiState = uiState,
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
        )
        Button(
            onClick = viewModel::toggleRecording,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(82.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = if (uiState.isRecording) Color(0xFF7C1513) else RecRed)
        ) { Text(if (uiState.isRecording) "STOP" else "REC", color = Color.White, fontWeight = FontWeight.Black) }
    }
}

@Composable
private fun CameraStage(
    uiState: AppUiState,
    viewModel: MainViewModel,
    overlays: @Composable BoxScope.() -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (uiState.permissionsGranted) {
            val recordingMode = uiState.isRecording || uiState.isPreparingRecording
            if (recordingMode) {
                // Preview en vivo via Camera2 (segunda superficie). Evita el congelado al grabar.
                RecordingPreview(viewModel, uiState.previewWidth, uiState.previewHeight, Modifier.fillMaxSize())
            } else {
                // Preview en reposo via CameraX.
                CameraPreview(viewModel, uiState.recordingProfileSummary, Modifier.fillMaxSize())
            }
        } else {
            Text("Permisos de camara, audio y Bluetooth requeridos", color = TextPrimary, modifier = Modifier.align(Alignment.Center))
        }
        overlays()
        uiState.errorMessage?.let {
            ErrorBanner(
                message = it,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 70.dp, end = 120.dp)
            )
        }
    }
}

@Composable
private fun TopHud(uiState: AppUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().panelBg(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("LogiQD CineCam", color = TextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        RecStatus(uiState)
        Spacer(Modifier.weight(1f))
        if (uiState.zoomRatio > 1.01f) {
            Text(
                "%.1fx".format(uiState.zoomRatio),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            "${uiState.selectedResolution} ${uiState.selectedFps} ${uiState.selectedCodec.shortCodec()} ${uiState.selectedDynamicRange}",
            color = Accent,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecStatus(uiState: AppUiState) {
    val recording = uiState.isRecording
    val preparing = uiState.isPreparingRecording
    var elapsed by remember { mutableStateOf(0) }
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(recording) {
        if (recording) {
            elapsed = 0
            while (true) {
                delay(1000)
                elapsed += 1
            }
        }
    }
    LaunchedEffect(recording) {
        if (recording) {
            while (true) {
                blink = !blink
                delay(500)
            }
        } else {
            blink = true
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if ((recording && blink) || preparing) RecRed else TextMuted)
        )
        Spacer(Modifier.width(6.dp))
        val label = when {
            recording -> "REC  ${formatTimecode(elapsed)}"
            preparing -> "PREP"
            else -> "STBY"
        }
        Text(
            label,
            color = if (recording || preparing) RecRed else TextMuted,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun formatTimecode(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

@Composable
private fun RightRail(uiState: AppUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = viewModel::toggleRecording,
            modifier = Modifier.size(86.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = if (uiState.isRecording) Color(0xFF7C1513) else RecRed)
        ) { Text(if (uiState.isRecording) "STOP" else "REC", color = Color.White, fontWeight = FontWeight.Black) }
        Spacer(Modifier.height(14.dp))
        SmallButton("Z+", viewModel::zoomIn)
        Spacer(Modifier.height(8.dp))
        SmallButton("Z-", viewModel::zoomOut)
    }
}

@Composable
private fun BottomStrip(uiState: AppUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    var panel by remember { mutableStateOf(MenuPanel.None) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().panelBg().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ParamButton("RES", uiState.selectedResolution) { panel = MenuPanel.Resolution }
            ParamButton("FPS", uiState.selectedFps) { panel = MenuPanel.Fps }
            ParamButton("CODEC", uiState.selectedCodec.shortCodec()) { panel = MenuPanel.Codec }
            ParamButton("LOOK", "${uiState.selectedDynamicRange}/${uiState.selectedPictureProfile}") { panel = MenuPanel.Look }
            ParamButton("GIMBAL", if (uiState.djiConnected) "OM2" else "OFF") { panel = MenuPanel.Gimbal }
            ParamButton("SET", "MENU") { panel = MenuPanel.Settings }
        }
        if (panel != MenuPanel.None) {
            MenuSheet(panel, uiState, viewModel, onClose = { panel = MenuPanel.None })
        }
    }
}

@Composable
private fun MenuSheet(panel: MenuPanel, uiState: AppUiState, viewModel: MainViewModel, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().panelBg().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(panelTitle(panel), color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SmallButton("Cerrar", onClose)
        }
        when (panel) {
            MenuPanel.Resolution -> OptionRow(uiState.availableResolutions, uiState.selectedResolution) {
                viewModel.setResolution(it)
            }
            MenuPanel.Fps -> OptionRow(uiState.availableFps, uiState.selectedFps) {
                viewModel.setFps(it)
            }
            MenuPanel.Codec -> {
                OptionRow(uiState.availableCodecs, uiState.selectedCodec) { viewModel.setCodec(it) }
                Text("Codec real se aplica en Camera2/MediaRecorder cuando el encoder del telefono lo permite.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            MenuPanel.Look -> {
                Text("Rango dinamico", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                OptionRow(uiState.availableDynamicRanges, uiState.selectedDynamicRange) { viewModel.setDynamicRange(it) }
                Text("Perfil", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                OptionRow(uiState.availablePictureProfiles, uiState.selectedPictureProfile) { viewModel.setPictureProfile(it) }
            }
            MenuPanel.Gimbal -> GimbalMenu(uiState, viewModel)
            MenuPanel.Settings -> SettingsMenu(uiState, viewModel) { /* keep open */ }
            MenuPanel.Debug -> DebugPanel(uiState)
            MenuPanel.None -> Unit
        }
    }
}

@Composable
private fun GimbalMenu(uiState: AppUiState, viewModel: MainViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton(if (uiState.djiConnected) "Desconectar OM2" else "Buscar OM2", if (uiState.djiConnected) viewModel::disconnectDji else viewModel::searchDji)
        SmallButton("Escanear camara", viewModel::refreshAdvancedCameraCapabilities)
    }
    if (uiState.djiConnected) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GimbalHorizonIndicator(
                pitch = uiState.gimbalPitch ?: 0f,
                roll = uiState.gimbalRoll ?: 0f,
                size = 76.dp
            )
            Column {
                Text("Modo: ${uiState.gimbalMode}", color = Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text("P ${uiState.gimbalPitch.deg()}  Y ${uiState.gimbalYaw.deg()}  R ${uiState.gimbalRoll.deg()}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("Modo de seguimiento", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("Follow", uiState.gimbalMode == "FOLLOW") { viewModel.setGimbalMode("FOLLOW") }
            ModeChip("FPV", uiState.gimbalMode == "FPV") { viewModel.setGimbalMode("FPV") }
            ModeChip("Free", uiState.gimbalMode == "FREE") { viewModel.setGimbalMode("FREE") }
        }
        Text("Control", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Recentrar", viewModel::recenterGimbal)
            SmallButton("Girar 180°", viewModel::rotateGimbalYaw180)
            SmallButton("Tilt ↑", viewModel::tiltGimbalUp)
            SmallButton("Tilt ↓", viewModel::tiltGimbalDown)
        }
    }
    Text(uiState.bluetoothState, color = TextMuted, style = MaterialTheme.typography.bodySmall)
    Text("Ultimo DJI: ${uiState.lastDjiEvent}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    uiState.foundDevices.forEach { device ->
        Row(Modifier.fillMaxWidth().clickable { viewModel.connectDji(device.id) }.padding(8.dp)) {
            Text(device.name, color = TextPrimary, modifier = Modifier.weight(1f))
            Text(device.description, color = TextMuted)
        }
    }
}

private fun Float?.deg(): String = this?.let { "%.0f°".format(it) } ?: "--"

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color(0xAA101417))
            .border(BorderStroke(1.dp, if (selected) Accent else Stroke), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, color = if (selected) Accent else TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
    }
}

/** Horizonte artificial: pitch desplaza la línea, roll la rota. */
@Composable
private fun GimbalHorizonIndicator(
    pitch: Float,
    roll: Float,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp
) {
    val pitchClamped = pitch.coerceIn(-45f, 45f)
    val rollRad = Math.toRadians(roll.toDouble()).toFloat()
    Canvas(modifier = modifier.size(size)) {
        val cx = this.size.width / 2
        val cy = this.size.height / 2
        val radius = this.size.width / 2 - 4.dp.toPx()
        drawCircle(color = Stroke, radius = radius, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        clipPath(Path().apply { addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius)) }) {
            val pitchOffset = pitchClamped / 45f * radius * 0.8f
            val lineLen = radius * 1.3f
            val cos = kotlin.math.cos(rollRad)
            val sin = kotlin.math.sin(rollRad)
            drawLine(
                color = Accent,
                start = Offset(cx - lineLen * cos, cy + pitchOffset + lineLen * sin),
                end = Offset(cx + lineLen * cos, cy + pitchOffset - lineLen * sin),
                strokeWidth = 2.dp.toPx()
            )
        }
        val ch = 9.dp.toPx()
        drawLine(Color.White, Offset(cx - ch, cy), Offset(cx + ch, cy), 1.dp.toPx())
        drawLine(Color.White, Offset(cx, cy - ch), Offset(cx, cy + ch), 1.dp.toPx())
    }
}

@Composable
private fun SettingsMenu(uiState: AppUiState, viewModel: MainViewModel, onDebug: () -> Unit) {
    SmallButton("Escanear capacidades", viewModel::refreshAdvancedCameraCapabilities)
    Text("Modos detectados", color = TextPrimary, fontWeight = FontWeight.Bold)
    Text(
        uiState.supportedVideoModes.take(18).joinToString(" | ") { "${it.resolution} ${it.fps} ${it.engine} cam${it.cameraId}" },
        color = TextMuted,
        style = MaterialTheme.typography.bodySmall
    )
    DebugPanel(uiState)
}

@Composable
private fun DebugPanel(uiState: AppUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DebugLine("Archivo", uiState.appliedRecordingMode)
        DebugLine("Camara", uiState.cameraMessage)
        DebugLine("Bluetooth", uiState.bluetoothState)
        DebugLine("Record", uiState.recordButtonState)
        DebugLine("Joystick", "x=${uiState.joystickX}, y=${uiState.joystickY}")
        uiState.errorMessage?.let { DebugLine("Error", it) }
    }
}

@Composable
private fun OptionRow(options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        options.forEach {
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (it == selected) Accent else Color.Transparent)
                    .border(BorderStroke(1.dp, if (it == selected) Accent else Stroke), RoundedCornerShape(7.dp))
                    .clickable { onSelected(it) }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(it, color = if (it == selected) Color.Black else TextPrimary, fontWeight = if (it == selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun ParamButton(label: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .border(BorderStroke(1.dp, Stroke), RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.labelSmall)
        Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, Stroke),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
    ) { Text(label) }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xAA101417)).border(BorderStroke(1.dp, Stroke), RoundedCornerShape(999.dp)).padding(horizontal = 9.dp, vertical = 5.dp)) {
        Text(label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xDD2A1111))
            .border(BorderStroke(1.dp, Color(0xFFB94B4B)), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            message,
            color = Color(0xFFFFD7D7),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label: ", color = TextMuted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text(value, color = TextPrimary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

private fun Modifier.panelBg(): Modifier {
    return this
        .clip(RoundedCornerShape(8.dp))
        .background(Panel)
        .border(BorderStroke(1.dp, Stroke), RoundedCornerShape(8.dp))
        .padding(10.dp)
}

private fun panelTitle(panel: MenuPanel): String {
    return when (panel) {
        MenuPanel.Resolution -> "Resolucion"
        MenuPanel.Fps -> "Fotogramas"
        MenuPanel.Codec -> "Codec"
        MenuPanel.Look -> "Look"
        MenuPanel.Gimbal -> "Gimbal"
        MenuPanel.Settings -> "Ajustes"
        MenuPanel.Debug -> "Debug"
        MenuPanel.None -> ""
    }
}

private fun String.shortCodec(): String = replace("H.265/", "").replace("H.264/", "")

private fun runtimePermissions(): Array<String> {
    val permissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.BLUETOOTH_SCAN
        permissions += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        permissions += Manifest.permission.BLUETOOTH
        permissions += Manifest.permission.BLUETOOTH_ADMIN
    }
    return permissions.toTypedArray()
}
