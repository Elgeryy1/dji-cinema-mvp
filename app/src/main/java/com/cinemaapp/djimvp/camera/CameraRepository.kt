package com.cinemaapp.djimvp.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Range
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

data class RecordingSettings(
    val resolution: String = "4K UHD",
    val fps: String = "30",
    val codec: String = "H.265/HEVC",
    val dynamicRange: String = "SDR",
    val pictureProfile: String = "Normal",
    val cameraId: String? = null
) {
    val summary: String
        get() = "$resolution ${fps}fps - $codec - $dynamicRange - $pictureProfile - cam ${cameraId ?: "auto"}"
}

data class CameraState(
    val isReady: Boolean = false,
    val isRecording: Boolean = false,
    val isPreparingRecording: Boolean = false,
    val previewWidth: Int = 1920,
    val previewHeight: Int = 1080,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val recordingSettings: RecordingSettings = RecordingSettings(),
    val appliedRecordingMode: String = "CameraX listo: perfil por defecto",
    val message: String = "Camara sin inicializar"
)

class CameraRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()
    private val camera2HighSpeedRecorder = Camera2HighSpeedRecorder(appContext)

    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var activeCamera2Recording = false
    private var lastBindKey: String? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lastLifecycleOwner: WeakReference<LifecycleOwner>? = null
    private var lastPreviewView: WeakReference<PreviewView>? = null
    private var smoothZoomTarget: Float? = null
    private val zoomHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recordingPreviewSurface: android.view.Surface? = null
    private var pendingStart = false
    // True desde que se pide grabar hasta que Camera2 libera la camara. Mientras esta activo,
    // CameraX NO debe vincularse o ambos clientes pelean por la camara (CAMERA_DISCONNECTED).
    private var recordingLifecycle = false

    /** Llamado por la UI cuando el SurfaceView de grabacion tiene su superficie lista (o null al destruirse). */
    fun setRecordingPreviewSurface(surface: android.view.Surface?) {
        recordingPreviewSurface = surface?.takeIf { it.isValid }
        if (pendingStart && recordingPreviewSurface != null) {
            pendingStart = false
            startCamera2Recording()
        }
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        lastLifecycleOwner = WeakReference(lifecycleOwner)
        lastPreviewView = WeakReference(previewView)
        val settings = _state.value.recordingSettings
        val bindKey = settings.summary
        if (activeCamera2Recording || recordingLifecycle) return
        if (camera != null && videoCapture != null && lastBindKey == bindKey) return
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            runCatching {
                Log.i("CINECAM_CAMERA", "bindCamera() settings=${settings.summary}")
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.unbindAll()
                videoCapture = null
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
                lastBindKey = bindKey
                updateZoomState("Preview CameraX; REC usa Camera2")
            }.onFailure { error ->
                Log.e("CINECAM_CAMERA", "bindCamera exception=${error}", error)
                _state.update { it.copy(isReady = false, message = "Error CameraX: ${error.message}") }
            }
        }, mainExecutor)
    }

    fun toggleRecording() {
        Log.i("CINECAM_CAMERA", "toggleRecording isRecording=${_state.value.isRecording}")
        if (_state.value.isRecording) stopRecording() else startRecording()
    }

    fun startRecording() {
        Log.i("CINECAM_CAMERA", "startRecording captureReady=${videoCapture != null} active=${activeRecording != null}")
        val settings = _state.value.recordingSettings
        if (settings.codec == "AV1") {
            _state.update {
                it.copy(
                    isRecording = false,
                    message = "Error Camera2: AV1 requiere grabador MediaCodec; MediaRecorder de Samsung no arranca AV1 aqui",
                    appliedRecordingMode = "AV1 pendiente de ruta MediaCodec/Muxer"
                )
            }
            return
        }
        val validation = validateCurrentSettings()
        if (validation.blockingReason != null) {
            Log.w("CINECAM_CAMERA", "recording blocked: ${validation.blockingReason}")
            _state.update {
                it.copy(
                    appliedRecordingMode = validation.appliedMode,
                    message = validation.blockingReason
                )
            }
            return
        }
        if (activeRecording != null) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(message = "Falta permiso RECORD_AUDIO") }
            return
        }
        // Entrar en modo "preparando": la UI muestra el SurfaceView de grabacion, cuya superficie
        // dispara el arranque real via setRecordingPreviewSurface(). Asi el preview no se congela.
        recordingLifecycle = true
        val previewSize = requestedSizeFor(settings.resolution)
        _state.update {
            it.copy(
                isPreparingRecording = true,
                previewWidth = previewSize.width,
                previewHeight = previewSize.height,
                message = "Preparando grabacion ${settings.resolution} ${settings.fps}fps"
            )
        }
        if (recordingPreviewSurface != null) {
            pendingStart = false
            startCamera2Recording()
        } else {
            pendingStart = true
        }
    }

    fun stopRecording() {
        if (activeCamera2Recording) {
            _state.update { it.copy(message = "Deteniendo Camera2") }
            camera2HighSpeedRecorder.stop()
            return
        }
        Log.i("CINECAM_CAMERA", "stopRecording active=${activeRecording != null}")
        activeRecording?.stop()
        activeRecording = null
        _state.update { it.copy(isRecording = false, message = "Deteniendo grabacion") }
    }

    private fun startCamera2Recording() {
        val settings = _state.value.recordingSettings
        runCatching { cameraProvider?.unbindAll() }
        camera = null
        videoCapture = null
        lastBindKey = null
        _state.update {
            it.copy(
                isReady = false,
                message = "Arrancando Camera2 ${settings.resolution} ${settings.fps}fps",
                appliedRecordingMode = "Camera2 ${settings.resolution} ${settings.fps}fps ${settings.codec} cam ${settings.cameraId ?: "auto"}"
            )
        }
        camera2HighSpeedRecorder.start(settings, recordingPreviewSurface, object : Camera2HighSpeedRecorder.Callback {
            override fun onStarted() {
                activeCamera2Recording = true
                mainExecutor.execute {
                    _state.update {
                        it.copy(
                            isRecording = true,
                            isPreparingRecording = false,
                            message = "Grabando Camera2",
                            appliedRecordingMode = "Camera2 ${settings.resolution} ${settings.fps}fps ${settings.codec} cam ${settings.cameraId ?: "auto"}"
                        )
                    }
                }
            }

            override fun onError(message: String) {
                activeCamera2Recording = false
                pendingStart = false
                recordingLifecycle = false
                mainExecutor.execute {
                    _state.update {
                        it.copy(
                            isRecording = false,
                            isPreparingRecording = false,
                            isReady = false,
                            message = "Error Camera2: $message",
                            appliedRecordingMode = "Camera2 fallo: $message"
                        )
                    }
                    rebindCameraXPreview()
                }
            }

            override fun onFinalized(actualInfo: String?) {
                activeCamera2Recording = false
                pendingStart = false
                recordingLifecycle = false
                mainExecutor.execute {
                    _state.update {
                        it.copy(
                            isRecording = false,
                            isPreparingRecording = false,
                            message = "Guardado Camera2 ${actualInfo ?: ""}".trim(),
                            appliedRecordingMode = "Camera2 ${settings.resolution} ${settings.fps}fps cam ${settings.cameraId ?: "auto"} | archivo: ${actualInfo ?: "desconocido"}"
                        )
                    }
                    rebindCameraXPreview()
                }
            }
        })
        mainHandler.postDelayed({
            val current = _state.value
            if (!current.isRecording && current.message.startsWith("Arrancando Camera2")) {
                camera2HighSpeedRecorder.forceRelease()
                pendingStart = false
                recordingLifecycle = false
                _state.update {
                    it.copy(
                        isRecording = false,
                        isPreparingRecording = false,
                        message = "Error Camera2: timeout arrancando ${settings.resolution} ${settings.fps}fps ${settings.codec}",
                        appliedRecordingMode = "Camera2 timeout: ${settings.summary}"
                    )
                }
                rebindCameraXPreview()
            }
        }, 5000L)
    }

    private fun rebindCameraXPreview() {
        val owner = lastLifecycleOwner?.get()
        val previewView = lastPreviewView?.get()
        if (owner != null && previewView != null) {
            bindCamera(owner, previewView)
        }
    }

    fun zoomInStep() = smoothZoomTo(_state.value.zoomRatio + zoomStep())

    fun zoomOutStep() = smoothZoomTo(_state.value.zoomRatio - zoomStep())

    private fun smoothZoomTo(ratio: Float) {
        val current = _state.value
        smoothZoomTarget = ratio.coerceIn(current.minZoomRatio, current.maxZoomRatio)
        animateZoomStep()
    }

    private fun animateZoomStep() {
        val target = smoothZoomTarget ?: return
        val current = _state.value.zoomRatio
        val delta = target - current
        if (abs(delta) < 0.015f) {
            setZoomRatio(target)
            smoothZoomTarget = null
            return
        }
        setZoomRatio(current + delta.coerceIn(-0.035f, 0.035f))
        zoomHandler.postDelayed({ animateZoomStep() }, 16L)
    }

    fun setZoomRatio(ratio: Float) {
        val current = _state.value
        val target = ratio.coerceIn(current.minZoomRatio, current.maxZoomRatio)
        val control = camera?.cameraControl ?: run {
            Log.w("CINECAM_CAMERA", "setZoomRatio ignored camera not ready target=$target")
            _state.update { it.copy(message = "Camara no lista para zoom") }
            return
        }
        Log.i("CINECAM_CAMERA", "setZoomRatio target=$target current=${current.zoomRatio}")
        control.setZoomRatio(target)
        _state.update { it.copy(zoomRatio = target, message = "Zoom ${"%.2f".format(Locale.US, target)}x") }
    }

    fun setRecordingSettings(settings: RecordingSettings) {
        lastBindKey = null
        val validation = validateSettings(settings)
        _state.update {
            it.copy(
                recordingSettings = settings,
                appliedRecordingMode = validation.appliedMode,
                message = validation.blockingReason ?: "Reconfigurando ${settings.summary}"
            )
        }
    }

    private fun zoomStep(): Float {
        val current = _state.value
        return ((current.maxZoomRatio - current.minZoomRatio) * 0.01f).coerceIn(0.04f, 0.10f)
    }

    private fun updateZoomState(message: String) {
        val settings = _state.value.recordingSettings
        val info = camera?.cameraInfo?.zoomState?.value
        _state.update {
            it.copy(
                isReady = true,
                zoomRatio = info?.zoomRatio ?: 1f,
                minZoomRatio = info?.minZoomRatio ?: 1f,
                maxZoomRatio = info?.maxZoomRatio ?: 1f,
                appliedRecordingMode = appliedModeFor(settings),
                message = message
            )
        }
    }

    private fun qualitySelectorFor(resolution: String): QualitySelector {
        val quality = when (resolution) {
            "8K UHD" -> Quality.UHD
            "4K UHD" -> Quality.UHD
            "QHD" -> Quality.FHD
            "1080p" -> Quality.FHD
            "720p" -> Quality.HD
            else -> Quality.HIGHEST
        }
        return QualitySelector.from(
            quality,
            androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(quality)
        )
    }

    private fun frameRateFor(fps: String): Range<Int> {
        val value = fps.toIntOrNull() ?: 30
        return Range(value, value)
    }

    private fun dynamicRangeFor(dynamicRange: String): DynamicRange {
        return when (dynamicRange) {
            "HLG10" -> DynamicRange.HLG_10_BIT
            else -> DynamicRange.SDR
        }
    }

    private fun appliedModeFor(settings: RecordingSettings): String {
        return "${settings.resolution} ${settings.fps}fps ${settings.codec} ${settings.dynamicRange} ${settings.pictureProfile} cam ${settings.cameraId ?: "auto"}"
    }

    private data class SettingsValidation(
        val blockingReason: String?,
        val appliedMode: String
    )

    private fun validateCurrentSettings(): SettingsValidation {
        return validateSettings(_state.value.recordingSettings)
    }

    private fun validateSettings(settings: RecordingSettings): SettingsValidation {
        val fps = settings.fps.toIntOrNull() ?: 30
        val requestedSize = requestedSizeFor(settings.resolution)
        val reasons = mutableListOf<String>()

        val camera2Support = readBackCameraSupport(requestedSize, fps, settings.cameraId)
        val forcedMode = settings.cameraId != null && (fps > 60 || requestedSize.width >= 7680)
        if (fps > 60 && !camera2Support.highSpeedSupported && !forcedMode) {
            reasons += "${settings.resolution}${fps} no esta anunciado por Camera2 high-speed"
        }
        if (!camera2Support.normalFpsSupported && fps <= 60 && !forcedMode) {
            reasons += "Camera2 no anuncia ${requestedSize.width}x${requestedSize.height}@${fps} en rangos normales"
        }

        val highSpeedHint = if (fps > 60 && camera2Support.highSpeedSupported) {
            "Camera2 HS OK ${requestedSize.width}x${requestedSize.height}@${fps}"
        } else if (fps > 60) {
            "Camera2 HS FORZADO ${requestedSize.width}x${requestedSize.height}@${fps}"
        } else {
            null
        }

        val forcedHint = if (requestedSize.width >= 7680 && !camera2Support.normalFpsSupported) {
            "Camera2 8K FORZADO ${requestedSize.width}x${requestedSize.height}@${fps}"
        } else {
            null
        }

        val applied = listOfNotNull(
            appliedModeFor(settings),
            highSpeedHint,
            forcedHint
        ).joinToString(" | ")

        return SettingsValidation(
            blockingReason = reasons.firstOrNull(),
            appliedMode = if (reasons.isEmpty()) applied else "NO APLICADO: ${reasons.first()} | $applied"
        )
    }

    private data class Camera2Support(
        val normalFpsSupported: Boolean,
        val highSpeedSupported: Boolean
    )

    private fun readBackCameraSupport(size: Size, fps: Int, cameraId: String?): Camera2Support {
        return runCatching {
            val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = cameraId ?: manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: return@runCatching Camera2Support(normalFpsSupported = false, highSpeedSupported = false)
            val c = manager.getCameraCharacteristics(backId)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val videoSizes = map?.getOutputSizes(android.media.MediaRecorder::class.java).orEmpty()
            val hasSize = videoSizes.any { it.width == size.width && it.height == size.height }
            val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val normalFps = (hasSize || camcorderProfileSupports(backId, size, fps)) &&
                fpsRanges.any { it.lower <= fps && it.upper >= fps }
            val highSpeed = map?.highSpeedVideoSizes
                ?.firstOrNull { it.width == size.width && it.height == size.height }
                ?.let { highSpeedSize ->
                    map.getHighSpeedVideoFpsRangesFor(highSpeedSize).any { it.lower <= fps && it.upper >= fps }
                } ?: false
            Camera2Support(normalFpsSupported = normalFps, highSpeedSupported = highSpeed)
        }.getOrDefault(Camera2Support(normalFpsSupported = true, highSpeedSupported = false))
    }

    private fun requestedSizeFor(resolution: String): Size {
        return when (resolution) {
            "8K UHD" -> Size(7680, 4320)
            "4K UHD" -> Size(3840, 2160)
            "QHD" -> Size(2560, 1440)
            "1080p" -> Size(1920, 1080)
            "720p" -> Size(1280, 720)
            else -> Size(1920, 1080)
        }
    }

    private fun qualityFor(resolution: String): Quality {
        return when (resolution) {
            "8K UHD", "4K UHD" -> Quality.UHD
            "QHD", "1080p" -> Quality.FHD
            "720p" -> Quality.HD
            else -> Quality.HIGHEST
        }
    }

    private fun readActualVideoInfo(uri: android.net.Uri): String? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(appContext, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val fps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            } else {
                null
            }
            retriever.release()
            listOfNotNull(
                width?.let { w -> height?.let { h -> "${w}x${h}" } },
                fps?.toFloatOrNull()?.let { "%.2ffps".format(Locale.US, it) }
            ).joinToString(" ")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun camcorderProfileSupports(cameraId: String, size: Size, fps: Int): Boolean {
        val numericId = cameraId.toIntOrNull() ?: return false
        val quality = when {
            size.width == 7680 && size.height == 4320 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                android.media.CamcorderProfile.QUALITY_8KUHD
            size.width == 3840 && size.height == 2160 -> android.media.CamcorderProfile.QUALITY_2160P
            size.width == 2560 && size.height == 1440 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                android.media.CamcorderProfile.QUALITY_QHD
            size.width == 1920 && size.height == 1080 -> android.media.CamcorderProfile.QUALITY_1080P
            size.width == 1280 && size.height == 720 -> android.media.CamcorderProfile.QUALITY_720P
            else -> return false
        }
        return runCatching {
            if (!android.media.CamcorderProfile.hasProfile(numericId, quality)) return false
            val profile = android.media.CamcorderProfile.get(numericId, quality)
            profile.videoFrameWidth == size.width &&
                profile.videoFrameHeight == size.height &&
                profile.videoFrameRate >= fps
        }.getOrDefault(false)
    }
}
