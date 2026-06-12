package com.cinemaapp.djimvp.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.CamcorderProfile
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class AdvancedCameraState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val summary: String = "Capacidades no escaneadas",
    val cameras: List<AdvancedCameraInfo> = emptyList(),
    val supportedVideoModes: List<SupportedVideoMode> = emptyList(),
    val videoEncoders: List<VideoEncoderInfo> = emptyList(),
    val error: String? = null
)

data class AdvancedCameraInfo(
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

data class VideoEncoderInfo(
    val name: String,
    val mimeType: String,
    val profiles: List<String>,
    val colorFormats: List<String>
)

data class SupportedVideoMode(
    val resolution: String,
    val fps: String,
    val engine: String,
    val cameraId: String
) {
    val label: String
        get() = "$resolution ${fps}fps"
}

class SamsungCameraCapabilitiesRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(AdvancedCameraState())
    val state: StateFlow<AdvancedCameraState> = _state.asStateFlow()

    suspend fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        val next = withContext(Dispatchers.Default) {
            runCatching {
                val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameras = manager.cameraIdList.map { id ->
                    readCamera(manager, id)
                }
                val supportedModes = readSupportedVideoModes(manager)
                val encoders = readVideoEncoders()
                AdvancedCameraState(
                    loaded = true,
                    loading = false,
                    summary = buildSummary(cameras, supportedModes, encoders),
                    cameras = cameras,
                    supportedVideoModes = supportedModes,
                    videoEncoders = encoders
                )
            }.getOrElse { error ->
                AdvancedCameraState(
                    loaded = false,
                    loading = false,
                    summary = "No se pudieron leer capacidades avanzadas",
                    error = error.message ?: error.toString()
                )
            }
        }
        _state.value = next
    }

    private fun readCamera(manager: CameraManager, cameraId: String): AdvancedCameraInfo {
        val c = manager.getCameraCharacteristics(cameraId)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val lensFacing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
        val hardwareLevel = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
        val videoSizes = map
            ?.getOutputSizes(MediaRecorder::class.java)
            ?.sortedWith(compareByDescending<Size> { it.width * it.height }.thenByDescending { it.width })
            ?.map { "${it.width}x${it.height}" }
            ?.distinct()
            .orEmpty()
        val highSpeedModes = map
            ?.highSpeedVideoSizes
            ?.flatMap { size ->
                map.getHighSpeedVideoFpsRangesFor(size).map { range ->
                    "${size.width}x${size.height}@${range.lower}-${range.upper}"
                }
            }
            ?.distinct()
            .orEmpty()
        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map(Range<Int>::toString)
            .orEmpty()
        val dynamicProfiles = readDynamicRangeProfiles(c)
        val camcorderProfiles = readCamcorderProfiles(cameraId)

        return AdvancedCameraInfo(
            cameraId = cameraId,
            lensFacing = lensFacing,
            hardwareLevel = hardwareLevel,
            physicalCameraIds = c.physicalCameraIds.toList().sorted(),
            videoSizes = videoSizes,
            highSpeedModes = highSpeedModes,
            fpsRanges = fpsRanges,
            dynamicRangeProfiles = dynamicProfiles,
            camcorderProfiles = camcorderProfiles
        )
    }

    private fun readDynamicRangeProfiles(c: CameraCharacteristics): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
        val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val supportsTenBit = capabilities.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
        )
        val profiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            ?.supportedProfiles
            ?.map(::dynamicRangeName)
            ?.sorted()
            .orEmpty()
        return if (supportsTenBit && profiles.isEmpty()) listOf("10-bit capability sin perfiles publicados") else profiles
    }

    private fun readCamcorderProfiles(cameraId: String): List<String> {
        val numericId = cameraId.toIntOrNull() ?: return emptyList()
        return camcorderQualities().mapNotNull { (quality, name) ->
            if (!CamcorderProfile.hasProfile(numericId, quality)) return@mapNotNull null
            val profile = runCatching { CamcorderProfile.get(numericId, quality) }.getOrNull()
            val details = profile?.let {
                "${it.videoFrameWidth}x${it.videoFrameHeight}@${it.videoFrameRate} ${videoCodecName(it.videoCodec)} ${it.videoBitRate / 1_000_000}Mbps"
            } ?: "disponible"
            "$name: $details"
        }
    }

    private fun readVideoEncoders(): List<VideoEncoderInfo> {
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { codec ->
                codec.supportedTypes
                    .filter { it.startsWith("video/") }
                    .map { mime ->
                        val caps = runCatching { codec.getCapabilitiesForType(mime) }.getOrNull()
                        VideoEncoderInfo(
                            name = codec.name,
                            mimeType = mime,
                            profiles = caps?.profileLevels?.map { profileLevelName(mime, it) }?.distinct().orEmpty(),
                            colorFormats = caps?.colorFormats?.map(::colorFormatName)?.distinct().orEmpty()
                        )
                    }
            }
            .sortedWith(compareBy<VideoEncoderInfo> { it.mimeType }.thenBy { it.name })
    }

    private fun readSupportedVideoModes(manager: CameraManager): List<SupportedVideoMode> {
        val backIds = manager.cameraIdList.filter { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        val detected = backIds.flatMap { backId ->
            readSupportedVideoModesForCamera(manager, backId)
        }
        val fallbackCam = backIds.firstOrNull() ?: "0"
        val forced = listOf(
            SupportedVideoMode("8K UHD", "24", "FORCED", fallbackCam),
            SupportedVideoMode("8K UHD", "30", "FORCED", fallbackCam),
            SupportedVideoMode("4K UHD", "120", "FORCED", fallbackCam)
        )
        return (detected + forced)
            .distinctBy { "${it.resolution}_${it.fps}_${it.engine}_${it.cameraId}" }
            .sortedWith(compareByDescending<SupportedVideoMode> { pixelsFor(it.resolution) }.thenBy { it.fps.toIntOrNull() ?: 0 }.thenBy { it.cameraId })
    }

    private fun readSupportedVideoModesForCamera(manager: CameraManager, backId: String): List<SupportedVideoMode> {
        val c = manager.getCameraCharacteristics(backId)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val videoSizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty().toSet()
        val normalRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val commonFps = listOf(24, 25, 30, 50, 60)
        val normal = recordingSizes().flatMap { (label, size) ->
            val profile = camcorderProfileFor(backId, size)
            if (size !in videoSizes && profile == null) return@flatMap emptyList()
            commonFps
                .filter { fps ->
                    (normalRanges.any { it.lower <= fps && it.upper >= fps } || profile?.videoFrameRate == fps) &&
                        (size in videoSizes || profile != null) &&
                        (profile == null || profile.videoFrameRate >= fps || size in videoSizes)
                }
                .map { fps -> SupportedVideoMode(label, fps.toString(), "Camera2", backId) }
        }
        val highSpeed = map.highSpeedVideoSizes.orEmpty().flatMap { size ->
            val label = resolutionLabel(size) ?: return@flatMap emptyList()
            map.getHighSpeedVideoFpsRangesFor(size)
                .flatMap { range -> listOf(range.lower, range.upper) }
                .distinct()
                .filter { it > 60 }
                .map { fps -> SupportedVideoMode(label, fps.toString(), "Camera2 HS", backId) }
        }
        return normal + highSpeed
    }

    private fun buildSummary(cameras: List<AdvancedCameraInfo>, modes: List<SupportedVideoMode>, encoders: List<VideoEncoderInfo>): String {
        val back = cameras.firstOrNull { it.lensFacing == "BACK" }
        val hdr = cameras.flatMap { it.dynamicRangeProfiles }.distinct().sorted()
        val codecNames = encoders.map { it.mimeType }.distinct().sorted()
        val topSizes = back?.videoSizes?.take(5).orEmpty().joinToString()
        val modeText = modes.take(8).joinToString { "${it.label} ${it.engine}" }
        return "Camaras=${cameras.size}; back=${back?.cameraId ?: "-"}; top=$topSizes; modos=$modeText; HDR=${hdr.ifEmpty { listOf("SDR") }.joinToString()}; codecs=${codecNames.joinToString()}"
    }

    private fun recordingSizes(): List<Pair<String, Size>> {
        return listOf(
            "8K UHD" to Size(7680, 4320),
            "4K UHD" to Size(3840, 2160),
            "QHD" to Size(2560, 1440),
            "1080p" to Size(1920, 1080),
            "720p" to Size(1280, 720)
        )
    }

    private fun resolutionLabel(size: Size): String? {
        return recordingSizes().firstOrNull { it.second.width == size.width && it.second.height == size.height }?.first
    }

    private fun pixelsFor(label: String): Int {
        val size = recordingSizes().firstOrNull { it.first == label }?.second ?: return 0
        return size.width * size.height
    }

    private fun camcorderProfileFor(cameraId: String, size: Size): CamcorderProfile? {
        val numericId = cameraId.toIntOrNull() ?: return null
        val quality = when {
            size.width == 7680 && size.height == 4320 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                CamcorderProfile.QUALITY_8KUHD
            size.width == 3840 && size.height == 2160 -> CamcorderProfile.QUALITY_2160P
            size.width == 2560 && size.height == 1440 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                CamcorderProfile.QUALITY_QHD
            size.width == 1920 && size.height == 1080 -> CamcorderProfile.QUALITY_1080P
            size.width == 1280 && size.height == 720 -> CamcorderProfile.QUALITY_720P
            else -> return null
        }
        return runCatching {
            if (!CamcorderProfile.hasProfile(numericId, quality)) return null
            CamcorderProfile.get(numericId, quality)
        }.getOrNull()?.takeIf {
            it.videoFrameWidth == size.width && it.videoFrameHeight == size.height
        }
    }

    private fun camcorderQualities(): List<Pair<Int, String>> {
        val base = mutableListOf(
            CamcorderProfile.QUALITY_LOW to "LOW",
            CamcorderProfile.QUALITY_HIGH to "HIGH",
            CamcorderProfile.QUALITY_480P to "480p",
            CamcorderProfile.QUALITY_720P to "720p",
            CamcorderProfile.QUALITY_1080P to "1080p",
            CamcorderProfile.QUALITY_2160P to "4K UHD"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            base += CamcorderProfile.QUALITY_2K to "2K"
            base += CamcorderProfile.QUALITY_QHD to "QHD"
            base += CamcorderProfile.QUALITY_8KUHD to "8K UHD"
        }
        return base
    }

    private fun dynamicRangeName(profile: Long): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return profile.toString()
        return when (profile) {
            DynamicRangeProfiles.STANDARD -> "STANDARD SDR"
            DynamicRangeProfiles.HLG10 -> "HLG10"
            DynamicRangeProfiles.HDR10 -> "HDR10"
            DynamicRangeProfiles.HDR10_PLUS -> "HDR10+"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF -> "Dolby Vision 10b ref"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO -> "Dolby Vision 10b ref PO"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM -> "Dolby Vision 10b OEM"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO -> "Dolby Vision 10b OEM PO"
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF -> "Dolby Vision 8b ref"
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO -> "Dolby Vision 8b ref PO"
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM -> "Dolby Vision 8b OEM"
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM_PO -> "Dolby Vision 8b OEM PO"
            else -> "Profile $profile"
        }
    }

    private fun videoCodecName(codec: Int): String {
        return when (codec) {
            MediaRecorder.VideoEncoder.H263 -> "H.263"
            MediaRecorder.VideoEncoder.H264 -> "H.264/AVC"
            MediaRecorder.VideoEncoder.HEVC -> "H.265/HEVC"
            MediaRecorder.VideoEncoder.MPEG_4_SP -> "MPEG-4 SP"
            MediaRecorder.VideoEncoder.VP8 -> "VP8"
            else -> "codec $codec"
        }
    }

    private fun profileLevelName(mime: String, profileLevel: MediaCodecInfo.CodecProfileLevel): String {
        return when (mime) {
            "video/avc" -> "AVC profile=${profileLevel.profile} level=${profileLevel.level}"
            "video/hevc" -> "HEVC profile=${profileLevel.profile} level=${profileLevel.level}"
            "video/av01" -> "AV1 profile=${profileLevel.profile} level=${profileLevel.level}"
            "video/x-vnd.on2.vp9" -> "VP9 profile=${profileLevel.profile} level=${profileLevel.level}"
            else -> "profile=${profileLevel.profile} level=${profileLevel.level}"
        }
    }

    private fun colorFormatName(format: Int): String {
        return when (format) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "YUV420 flexible"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface -> "Surface"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 -> "YUV P010 10-bit"
            else -> "format $format"
        }
    }
}
