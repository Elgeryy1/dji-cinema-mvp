package com.cinemaapp.djimvp.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.WindowManager
import android.view.Surface
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

class Camera2HighSpeedRecorder(private val context: Context) {
    private val appContext = context.applicationContext
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputFd: android.os.ParcelFileDescriptor? = null
    private var callback: Callback? = null
    private var previewSurface: Surface? = null

    interface Callback {
        fun onStarted()
        fun onError(message: String)
        fun onFinalized(actualInfo: String?)
    }

    fun start(settings: RecordingSettings, previewSurface: Surface?, callback: Callback) {
        this.callback = callback
        this.previewSurface = previewSurface?.takeIf { it.isValid }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            callback.onError("Falta permiso CAMERA")
            return
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callback.onError("Falta permiso RECORD_AUDIO")
            return
        }
        startThread()
        handler?.post {
            startOnCameraThread(settings, callback)
        } ?: callback.onError("No se pudo arrancar hilo Camera2")
    }

    private fun startOnCameraThread(settings: RecordingSettings, callback: Callback) {
        runCatching {
            val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val fps = settings.fps.toIntOrNull() ?: 120
            val size = sizeFor(settings.resolution)
            val cameraId = settings.cameraId
                ?: findCameraForMode(manager, size, fps)
            val highSpeed = fps > 60
            val useConstrainedHighSpeed = highSpeedSessionSupported(manager, cameraId, size, fps)
            ensureSupported(manager, cameraId, size, fps, highSpeed)
            val av1Supported = hasAv1SurfaceEncoder()
            Log.i(
                "CINECAM_CAMERA",
                "Camera2 start ${settings.summary} camera=$cameraId size=${size.width}x${size.height} fps=$fps highSpeed=$useConstrainedHighSpeed av1Surface=$av1Supported"
            )
            if (settings.codec == "AV1" && !av1Supported) {
                error("AV1 no esta disponible como encoder de video en este telefono")
            }
            val uri = createOutputUri()
            val fd = appContext.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("No se pudo abrir MediaStore")
            outputUri = uri
            outputFd = fd
            val mediaRecorder = buildRecorder(manager, cameraId, settings, size, fps, fd.fileDescriptor)
            recorder = mediaRecorder
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    runCatching {
                        createRecordingSession(device, mediaRecorder.surface, previewSurface, fps, useConstrainedHighSpeed)
                    }.onFailure { error ->
                        dispatchError("Camera2 no pudo crear sesion: ${error.message ?: error}")
                        cleanup()
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    dispatchError("Camera2 desconectada")
                    cleanup()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    dispatchError("Camera2 error $error")
                    cleanup()
                }
            }, handler)
        }.onFailure { error ->
            dispatchError(error.message ?: error.toString())
            cleanup()
        }
    }

    fun stop() {
        handler?.post {
            runCatching {
                session?.stopRepeating()
                session?.abortCaptures()
            }
            val actualUri = outputUri
            runCatching { recorder?.stop() }.onFailure {
                dispatchError("Camera2 stop fallo: ${it.message}")
            }
            cleanup(closeCallback = false)
            callback?.onFinalized(actualUri?.let(::readActualVideoInfo))
            callback = null
        }
    }

    fun forceRelease() {
        runCatching { cleanup() }
    }

    private fun createRecordingSession(
        device: CameraDevice,
        recorderSurface: Surface,
        previewSurface: Surface?,
        fps: Int,
        highSpeed: Boolean
    ) {
        val livePreview = previewSurface?.takeIf { it.isValid }
        val targets = listOfNotNull(livePreview, recorderSurface)
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                session = captureSession
                runCatching {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        livePreview?.let { addTarget(it) }
                        addTarget(recorderSurface)
                        set(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                    }.build()
                    if (highSpeed && captureSession is CameraConstrainedHighSpeedCaptureSession) {
                        val requests = captureSession.createHighSpeedRequestList(request)
                        captureSession.setRepeatingBurst(requests, null, handler)
                    } else {
                        captureSession.setRepeatingRequest(request, null, handler)
                    }
                    recorder?.start()
                    callback?.onStarted()
                }.onFailure { error ->
                    dispatchError("Camera2 session fallo: ${error.message}")
                    cleanup()
                }
            }

            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                dispatchError("Camera2 no pudo configurar sesion")
                cleanup()
            }
        }

        if (highSpeed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                device.createConstrainedHighSpeedCaptureSession(targets, stateCallback, handler)
            }.onFailure { error ->
                dispatchError("High-speed no aceptado: ${error.message ?: error}")
                cleanup()
            }
        } else {
            runCatching {
                device.createCaptureSession(targets, stateCallback, handler)
            }.onFailure { error ->
                dispatchError("Sesion Camera2 no aceptada: ${error.message ?: error}")
                cleanup()
            }
        }
    }

    private fun buildRecorder(
        manager: CameraManager,
        cameraId: String,
        settings: RecordingSettings,
        size: Size,
        fps: Int,
        fd: java.io.FileDescriptor
    ): MediaRecorder {
        return MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(fd)
            setVideoEncodingBitRate(bitRateFor(size, fps))
            setVideoFrameRate(fps)
            setVideoSize(size.width, size.height)
            setOrientationHint(orientationHint(manager, cameraId))
            setVideoEncoder(
                when {
                    settings.codec == "AV1" && Build.VERSION.SDK_INT >= 34 -> MediaRecorder.VideoEncoder.AV1
                    settings.codec == "H.265/HEVC" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> MediaRecorder.VideoEncoder.HEVC
                    else -> MediaRecorder.VideoEncoder.H264
                }
            )
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(192_000)
            setAudioSamplingRate(48_000)
            prepare()
        }
    }

    private fun ensureSupported(manager: CameraManager, cameraId: String, size: Size, fps: Int, highSpeed: Boolean) {
        val forced = fps > 60 || size.width >= 7680 || highSpeed
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Stream map no disponible")
        if (!highSpeed) {
            val normalSizeOk = map.getOutputSizes(MediaRecorder::class.java).orEmpty().any {
                it.width == size.width && it.height == size.height
            }
            val profileOk = camcorderProfileFor(cameraId, size, fps) != null
            if (!normalSizeOk && !profileOk && !forced) {
                error("${size.width}x${size.height}@${fps} no esta anunciado para Camera2/MediaRecorder")
            }
            return
        }
        val matchedSize = map.highSpeedVideoSizes.firstOrNull {
            it.width == size.width && it.height == size.height
        } ?: if (forced) return else error("${size.width}x${size.height} no esta en high-speed Camera2")
        val rangeOk = map.getHighSpeedVideoFpsRangesFor(matchedSize).any {
            it.lower <= fps && it.upper >= fps
        }
        if (!rangeOk) error("${size.width}x${size.height}@${fps} no esta en high-speed Camera2")
    }

    private fun highSpeedSessionSupported(manager: CameraManager, cameraId: String, size: Size, fps: Int): Boolean {
        return runCatching {
            val map = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@runCatching false
            val matchedSize = map.highSpeedVideoSizes.firstOrNull {
                it.width == size.width && it.height == size.height
            } ?: return@runCatching false
            map.getHighSpeedVideoFpsRangesFor(matchedSize).any {
                it.lower <= fps && it.upper >= fps
            }
        }.getOrDefault(false)
    }

    private fun hasAv1SurfaceEncoder(): Boolean {
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                codec.isEncoder && codec.supportedTypes.any { it.equals("video/av01", ignoreCase = true) } &&
                    runCatching {
                        codec.getCapabilitiesForType("video/av01").colorFormats.contains(
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        )
                    }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }

    private fun createOutputUri(): Uri {
        val name = "LogiQD_C2_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LogiQD CineCam")
            }
        }
        return appContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("No se pudo crear MediaStore Uri")
    }

    private fun startThread() {
        if (thread != null) return
        thread = HandlerThread("LogiQD-Camera2-HS").also { it.start() }
        handler = Handler(thread!!.looper)
    }

    private fun cleanup(closeCallback: Boolean = true) {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        runCatching { outputFd?.close() }
        runCatching { thread?.quitSafely() }
        session = null
        camera = null
        recorder = null
        outputFd = null
        thread = null
        handler = null
        previewSurface = null
        if (closeCallback) callback = null
    }

    private fun dispatchError(message: String) {
        Log.e("CINECAM_CAMERA", message)
        callback?.onError(message)
    }

    private fun readActualVideoInfo(uri: Uri): String? {
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

    private fun sizeFor(resolution: String): Size {
        return when (resolution) {
            "8K UHD" -> Size(7680, 4320)
            "4K UHD" -> Size(3840, 2160)
            "QHD" -> Size(2560, 1440)
            "1080p" -> Size(1920, 1080)
            "720p" -> Size(1280, 720)
            else -> Size(1920, 1080)
        }
    }

    private fun bitRateFor(size: Size, fps: Int): Int {
        val pixels = size.width * size.height
        val base = when {
            pixels >= 7680 * 4320 -> 180_000_000
            pixels >= 3840 * 2160 -> 90_000_000
            pixels >= 1920 * 1080 -> 35_000_000
            else -> 16_000_000
        }
        return (base * (fps / 30f)).toInt().coerceAtMost(240_000_000)
    }

    private fun orientationHint(manager: CameraManager, cameraId: String): Int {
        val rotation = runCatching {
            @Suppress("DEPRECATION")
            (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }.getOrDefault(Surface.ROTATION_0)
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensorOrientation = runCatching {
            manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        }.getOrDefault(90)
        return (sensorOrientation - degrees + 360) % 360
    }

    private fun findCameraForMode(manager: CameraManager, size: Size, fps: Int): String {
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK &&
                runCatching {
                    ensureSupported(manager, id, size, fps, fps > 60)
                    true
                }.getOrDefault(false)
        } ?: error("Ninguna camara trasera anuncia ${size.width}x${size.height}@${fps}")
    }

    private fun camcorderProfileFor(cameraId: String, size: Size, fps: Int): android.media.CamcorderProfile? {
        val numericId = cameraId.toIntOrNull() ?: return null
        val quality = when {
            size.width == 7680 && size.height == 4320 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                android.media.CamcorderProfile.QUALITY_8KUHD
            size.width == 3840 && size.height == 2160 -> android.media.CamcorderProfile.QUALITY_2160P
            size.width == 2560 && size.height == 1440 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                android.media.CamcorderProfile.QUALITY_QHD
            size.width == 1920 && size.height == 1080 -> android.media.CamcorderProfile.QUALITY_1080P
            size.width == 1280 && size.height == 720 -> android.media.CamcorderProfile.QUALITY_720P
            else -> return null
        }
        return runCatching {
            if (!android.media.CamcorderProfile.hasProfile(numericId, quality)) return null
            android.media.CamcorderProfile.get(numericId, quality)
        }.getOrNull()?.takeIf {
            it.videoFrameWidth == size.width && it.videoFrameHeight == size.height && it.videoFrameRate >= fps
        }
    }
}
