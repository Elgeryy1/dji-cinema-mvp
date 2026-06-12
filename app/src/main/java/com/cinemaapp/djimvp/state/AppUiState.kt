package com.cinemaapp.djimvp.state

data class AppUiState(
    val permissionsGranted: Boolean = false,
    val cameraReady: Boolean = false,
    val isRecording: Boolean = false,
    val isPreparingRecording: Boolean = false,
    val previewWidth: Int = 1920,
    val previewHeight: Int = 1080,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val cameraMessage: String = "Camara sin inicializar",
    val selectedResolution: String = "4K UHD",
    val selectedFps: String = "30",
    val selectedCodec: String = "H.265/HEVC",
    val selectedDynamicRange: String = "SDR",
    val selectedPictureProfile: String = "Normal",
    val selectedCameraId: String = "auto",
    val recordingProfileSummary: String = "4K UHD 30fps - H.265/HEVC - SDR - Normal",
    val appliedRecordingMode: String = "CameraX listo: perfil por defecto",
    val advancedCameraLoaded: Boolean = false,
    val advancedCameraLoading: Boolean = false,
    val advancedCameraSummary: String = "Capacidades no escaneadas",
    val advancedCameraCameras: List<AdvancedCameraUi> = emptyList(),
    val supportedVideoModes: List<SupportedVideoModeUi> = emptyList(),
    val availableResolutions: List<String> = listOf("4K UHD", "1080p", "720p"),
    val availableFps: List<String> = listOf("24", "25", "30", "50", "60"),
    val availableCodecs: List<String> = listOf("H.265/HEVC", "H.264/AVC"),
    val availableDynamicRanges: List<String> = listOf("SDR"),
    val availablePictureProfiles: List<String> = listOf("Normal"),
    val advancedCameraEncoders: List<VideoEncoderUi> = emptyList(),
    val advancedCameraError: String? = null,
    val sdkRegistered: Boolean = false,
    val sdkRegistrationState: String = "No registrado",
    val runtimePackageName: String = "",
    val manifestHasDjiKey: Boolean = false,
    val manifestDjiKeyLength: Int = 0,
    val manifestDjiKeyLast4: String = "",
    val djiHelperInstallAttempted: Boolean = false,
    val djiHelperInstallSucceeded: Boolean = false,
    val djiHelperInstallError: String? = null,
    val djiHelperInstallRawError: String? = null,
    val sdkRegisterError: String? = null,
    val sdkRegisterRawError: String? = null,
    val sdkRegisterAttempted: Boolean = false,
    val sdkRegisterInProgress: Boolean = false,
    val bluetoothState: String = "Inactivo",
    val isSearchingDji: Boolean = false,
    val djiConnected: Boolean = false,
    val connectedDeviceName: String = "",
    val foundDevices: List<DjiDeviceUi> = emptyList(),
    val lastDjiEvent: String = "Sin eventos",
    val joystickX: Int = 0,
    val joystickY: Int = 0,
    val zoomSliderState: String = "IDLE",
    val triggerState: String = "IDLE",
    val modeButtonState: String = "IDLE",
    val recordButtonState: String = "IDLE",
    val gimbalPitch: Float? = null,
    val gimbalYaw: Float? = null,
    val gimbalRoll: Float? = null,
    val gimbalMode: String = "FOLLOW",
    val errorMessage: String? = null
)

data class DjiDeviceUi(
    val id: String,
    val name: String,
    val description: String
)

data class AdvancedCameraUi(
    val cameraId: String,
    val lensFacing: String,
    val hardwareLevel: String,
    val physicalCameraIds: List<String>,
    val videoSizes: List<String>,
    val highSpeedModes: List<String>,
    val fpsRanges: List<String>,
    val dynamicRangeProfiles: List<String>,
    val camcorderProfiles: List<String>
)

data class VideoEncoderUi(
    val name: String,
    val mimeType: String,
    val profiles: List<String>,
    val colorFormats: List<String>
)

data class SupportedVideoModeUi(
    val resolution: String,
    val fps: String,
    val engine: String,
    val cameraId: String
)
