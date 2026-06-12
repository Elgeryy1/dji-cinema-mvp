package com.cinemaapp.djimvp

import android.app.Application
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.cinemaapp.djimvp.camera.CameraRepository
import com.cinemaapp.djimvp.camera.RecordingSettings
import com.cinemaapp.djimvp.camera.SamsungCameraCapabilitiesRepository
import com.cinemaapp.djimvp.dji.DjiControlEvent
import com.cinemaapp.djimvp.dji.DjiRepository
import com.cinemaapp.djimvp.state.AppUiState
import com.cinemaapp.djimvp.state.AdvancedCameraUi
import com.cinemaapp.djimvp.state.DjiDeviceUi
import com.cinemaapp.djimvp.state.SupportedVideoModeUi
import com.cinemaapp.djimvp.state.VideoEncoderUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val cameraRepository = CameraRepository(application)
    private val advancedCameraRepository = SamsungCameraCapabilitiesRepository(application)
    private val djiRepository = DjiRepository(application)
    private val _permissionsGranted = MutableStateFlow(false)
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                _permissionsGranted,
                cameraRepository.state,
                advancedCameraRepository.state,
                djiRepository.state
            ) { permissions, camera, advanced, dji ->
                val supportedModes = advanced.supportedVideoModes.map {
                    SupportedVideoModeUi(it.resolution, it.fps, it.engine, it.cameraId)
                }
                val availableCodecs = buildAvailableCodecs(advanced.videoEncoders)
                val availableRanges = buildAvailableDynamicRanges(advanced.cameras)
                AppUiState(
                    permissionsGranted = permissions,
                    cameraReady = camera.isReady,
                    isRecording = camera.isRecording,
                    isPreparingRecording = camera.isPreparingRecording,
                    previewWidth = camera.previewWidth,
                    previewHeight = camera.previewHeight,
                    zoomRatio = camera.zoomRatio,
                    minZoomRatio = camera.minZoomRatio,
                    maxZoomRatio = camera.maxZoomRatio,
                    cameraMessage = camera.message,
                    selectedResolution = camera.recordingSettings.resolution,
                    selectedFps = camera.recordingSettings.fps,
                    selectedCodec = camera.recordingSettings.codec,
                    selectedDynamicRange = camera.recordingSettings.dynamicRange,
                    selectedPictureProfile = camera.recordingSettings.pictureProfile,
                    selectedCameraId = camera.recordingSettings.cameraId ?: "auto",
                    recordingProfileSummary = camera.recordingSettings.summary,
                    appliedRecordingMode = camera.appliedRecordingMode,
                    advancedCameraLoaded = advanced.loaded,
                    advancedCameraLoading = advanced.loading,
                    advancedCameraSummary = advanced.summary,
                    advancedCameraCameras = advanced.cameras.map {
                        AdvancedCameraUi(
                            cameraId = it.cameraId,
                            lensFacing = it.lensFacing,
                            hardwareLevel = it.hardwareLevel,
                            physicalCameraIds = it.physicalCameraIds,
                            videoSizes = it.videoSizes,
                            highSpeedModes = it.highSpeedModes,
                            fpsRanges = it.fpsRanges,
                            dynamicRangeProfiles = it.dynamicRangeProfiles,
                            camcorderProfiles = it.camcorderProfiles
                        )
                    },
                    supportedVideoModes = supportedModes,
                    availableResolutions = supportedModes.map { it.resolution }.distinct().ifEmpty {
                        listOf("4K UHD", "1080p", "720p")
                    },
                    availableFps = supportedModes
                        .filter { it.resolution == camera.recordingSettings.resolution }
                        .map { it.fps }
                        .distinct()
                        .ifEmpty { listOf("24", "25", "30", "50", "60") },
                    availableCodecs = availableCodecs,
                    availableDynamicRanges = availableRanges,
                    availablePictureProfiles = listOf("Normal"),
                    advancedCameraEncoders = advanced.videoEncoders.map {
                        VideoEncoderUi(
                            name = it.name,
                            mimeType = it.mimeType,
                            profiles = it.profiles,
                            colorFormats = it.colorFormats
                        )
                    },
                    advancedCameraError = advanced.error,
                    sdkRegistered = dji.sdkRegistered,
                    sdkRegistrationState = dji.sdkRegistrationState,
                    runtimePackageName = dji.runtimePackageName,
                    manifestHasDjiKey = dji.manifestHasDjiKey,
                    manifestDjiKeyLength = dji.manifestDjiKeyLength,
                    manifestDjiKeyLast4 = dji.manifestDjiKeyLast4,
                    djiHelperInstallAttempted = dji.djiHelperInstallAttempted,
                    djiHelperInstallSucceeded = dji.djiHelperInstallSucceeded,
                    djiHelperInstallError = dji.djiHelperInstallError,
                    djiHelperInstallRawError = dji.djiHelperInstallRawError,
                    sdkRegisterError = dji.sdkRegisterError,
                    sdkRegisterRawError = dji.sdkRegisterRawError,
                    sdkRegisterAttempted = dji.sdkRegisterAttempted,
                    sdkRegisterInProgress = dji.sdkRegisterInProgress,
                    bluetoothState = dji.bluetoothState,
                    isSearchingDji = dji.isSearching,
                    djiConnected = dji.connected,
                    connectedDeviceName = dji.connectedDeviceName,
                    foundDevices = dji.devices.map {
                        DjiDeviceUi(id = it.id, name = it.name, description = it.description)
                    },
                    lastDjiEvent = dji.lastEvent,
                    joystickX = dji.joystickX,
                    joystickY = dji.joystickY,
                    zoomSliderState = dji.zoomSliderState,
                    triggerState = dji.triggerState,
                    modeButtonState = dji.modeButtonState,
                    recordButtonState = dji.recordButtonState,
                    gimbalPitch = dji.gimbalPitch,
                    gimbalYaw = dji.gimbalYaw,
                    gimbalRoll = dji.gimbalRoll,
                    gimbalMode = dji.gimbalMode,
                    errorMessage = dji.errorMessage ?: advanced.error ?: camera.message.takeIf(::isUserVisibleCameraError)
                )
            }.collect { next -> _uiState.value = next }
        }

        viewModelScope.launch {
            djiRepository.events.collect { event ->
                when (event) {
                    DjiControlEvent.RecordClick -> cameraRepository.toggleRecording()
                    DjiControlEvent.TriggerRecenter -> djiRepository.recenterGimbal()
                    DjiControlEvent.ZoomIn -> cameraRepository.zoomInStep()
                    DjiControlEvent.ZoomOut -> cameraRepository.zoomOutStep()
                    is DjiControlEvent.Joystick -> djiRepository.rotateGimbalFromJoystick(event.x, event.y)
                }
            }
        }
    }

    fun setPermissionsGranted(granted: Boolean) {
        _permissionsGranted.value = granted
        if (granted) {
            registerDji()
            refreshAdvancedCameraCapabilities()
        }
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (_permissionsGranted.value) {
            cameraRepository.bindCamera(lifecycleOwner, previewView)
        }
    }

    fun toggleRecording() = cameraRepository.toggleRecording()

    fun setRecordingPreviewSurface(surface: android.view.Surface?) =
        cameraRepository.setRecordingPreviewSurface(surface)

    fun zoomIn() = cameraRepository.zoomInStep()

    fun zoomOut() = cameraRepository.zoomOutStep()

    fun setResolution(value: String) {
        val current = cameraRepository.state.value.recordingSettings
        val mode = bestMode(value, current.fps)
        cameraRepository.setRecordingSettings(
            current.copy(
                resolution = mode?.resolution ?: value,
                fps = mode?.fps ?: current.fps,
                cameraId = mode?.cameraId ?: current.cameraId
            )
        )
    }

    fun setFps(value: String) {
        val current = cameraRepository.state.value.recordingSettings
        val mode = bestMode(current.resolution, value)
        cameraRepository.setRecordingSettings(
            current.copy(
                fps = mode?.fps ?: value,
                cameraId = mode?.cameraId ?: current.cameraId
            )
        )
    }

    fun setCodec(value: String) {
        val current = cameraRepository.state.value.recordingSettings
        cameraRepository.setRecordingSettings(current.copy(codec = value))
    }

    fun setDynamicRange(value: String) {
        val current = cameraRepository.state.value.recordingSettings
        cameraRepository.setRecordingSettings(current.copy(dynamicRange = value))
    }

    fun setPictureProfile(value: String) {
        val current = cameraRepository.state.value.recordingSettings
        cameraRepository.setRecordingSettings(current.copy(pictureProfile = value))
    }

    fun refreshAdvancedCameraCapabilities() {
        viewModelScope.launch {
            advancedCameraRepository.refresh()
            normalizeRecordingSettings()
            normalizeCodecSettings()
        }
    }

    fun registerDji() = djiRepository.registerSdk()

    fun searchDji() = djiRepository.searchBluetoothProducts()

    fun connectDji(id: String) = djiRepository.connectToDevice(id)

    fun disconnectDji() = djiRepository.disconnect()

    fun setGimbalFollowMode() = djiRepository.setGimbalFollowMode()

    fun setGimbalMode(mode: String) = djiRepository.setGimbalMode(mode)

    fun recenterGimbal() = djiRepository.recenterGimbal()

    fun rotateGimbalYaw180() = djiRepository.rotateGimbalYaw180()

    fun tiltGimbalUp() = djiRepository.tiltGimbal(15f)

    fun tiltGimbalDown() = djiRepository.tiltGimbal(-15f)

    fun setGimbalSpeedFactor(value: Float) {
        djiRepository.gimbalSpeedFactor = value
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun bestFpsForResolution(resolution: String, currentFps: String): String {
        val fpsOptions = _uiState.value.supportedVideoModes
            .filter { it.resolution == resolution }
            .map { it.fps }
            .distinct()
        return if (currentFps in fpsOptions) currentFps else fpsOptions.firstOrNull() ?: currentFps
    }

    private fun bestMode(resolution: String, fps: String): SupportedVideoModeUi? {
        val modes = _uiState.value.supportedVideoModes.filter { it.resolution == resolution }
        return modes.firstOrNull { it.fps == fps }
            ?: modes.firstOrNull()
    }

    private fun normalizeRecordingSettings() {
        val modes = advancedCameraRepository.state.value.supportedVideoModes
        if (modes.isEmpty()) return
        val current = cameraRepository.state.value.recordingSettings
        val matching = modes.firstOrNull {
            it.resolution == current.resolution && it.fps == current.fps
        }
        if (matching != null) return
        val replacement = modes.firstOrNull { it.resolution == current.resolution } ?: modes.first()
        cameraRepository.setRecordingSettings(
            current.copy(
                resolution = replacement.resolution,
                fps = replacement.fps,
                cameraId = replacement.cameraId
            )
        )
    }

    private fun normalizeCodecSettings() {
        val codecs = buildAvailableCodecs(advancedCameraRepository.state.value.videoEncoders)
        val current = cameraRepository.state.value.recordingSettings
        if (current.codec in codecs) return
        cameraRepository.setRecordingSettings(current.copy(codec = codecs.firstOrNull() ?: "H.265/HEVC"))
    }

    private fun buildAvailableCodecs(encoders: List<com.cinemaapp.djimvp.camera.VideoEncoderInfo>): List<String> {
        val mimes = encoders.map { it.mimeType }.toSet()
        return buildList {
            if ("video/hevc" in mimes) add("H.265/HEVC")
            if ("video/avc" in mimes) add("H.264/AVC")
        }.ifEmpty { listOf("H.265/HEVC", "H.264/AVC") }
    }

    private fun buildAvailableDynamicRanges(cameras: List<com.cinemaapp.djimvp.camera.AdvancedCameraInfo>): List<String> {
        val ranges = cameras
            .filter { it.lensFacing == "BACK" }
            .flatMap { it.dynamicRangeProfiles }
            .toSet()
        return buildList {
            add("SDR")
            if (ranges.any { it.contains("HLG10") }) add("HLG10")
            if (ranges.any { it == "HDR10" }) add("HDR10")
            if (ranges.any { it == "HDR10+" }) add("HDR10+")
            if (ranges.any { it.contains("Dolby Vision") }) add("Dolby Vision")
        }
    }

    private fun isUserVisibleCameraError(message: String): Boolean {
        return message.startsWith("Error") ||
            message.contains("fallo", ignoreCase = true) ||
            message.contains("no esta", ignoreCase = true) ||
            message.contains("no disponible", ignoreCase = true) ||
            message.contains("AV1", ignoreCase = true)
    }
}
