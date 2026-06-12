# MEJORA-01 — Pipeline AV1 via MediaCodec + MediaMuxer

| Campo | Valor |
|---|---|
| Prioridad | Alta |
| Tipo | Backend |
| Esfuerzo estimado | 1-2 semanas |
| Dispositivos objetivo | Samsung Galaxy S23/S24/S25 con Android 14+ |

---

## Descripción

Implementar la grabación AV1 usando `MediaCodec` con encoder `video/av01` + `MediaMuxer` directamente. AV1 ofrece un **40% menos de tamaño de archivo** que H.265 a igual calidad perceptual, sin licencias de pago.

`MediaRecorder` no soporta AV1 en Samsung. La única vía es la pipeline manual `CameraDevice Surface → MediaCodec AV1 → MediaMuxer`.

---

## Por qué AV1 vale la pena

- H.264 → bitrate 35 Mbps para 4K 30fps aceptable
- H.265 → bitrate 20 Mbps para misma calidad
- AV1 → bitrate 12-15 Mbps para misma calidad
- Los Galaxy S23 Ultra y S24 Ultra tienen encoder AV1 hardware con soporte `COLOR_FormatSurface`

---

## Implementación paso a paso

### Paso 1 — Verificar soporte AV1 (ya implementado parcialmente)

```kotlin
// En VideoInfoUtils.kt (extraído del BUG-01):
fun hasAv1SurfaceEncoder(): Boolean {
    return runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder &&
            codec.supportedTypes.any { it.equals("video/av01", ignoreCase = true) } &&
            codec.getCapabilitiesForType("video/av01")
                .colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
    }.getOrDefault(false)
}
```

### Paso 2 — Crear la clase `Camera2Av1Recorder`

Crear `app/src/main/java/com/cinemaapp/djimvp/camera/Camera2Av1Recorder.kt`:

```kotlin
package com.cinemaapp.djimvp.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Graba vídeo AV1 usando Camera2 Surface + MediaCodec + MediaMuxer.
 * No usa MediaRecorder (que no soporta AV1 en Samsung).
 */
class Camera2Av1Recorder(private val context: Context) {

    companion object {
        private const val TAG = "CINECAM_AV1"
        private const val MIME_AV1 = "video/av01"
    }

    interface Callback {
        fun onStarted()
        fun onError(message: String)
        fun onFinalized(actualInfo: String?)
    }

    private val appContext = context.applicationContext
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var encoderSurface: android.view.Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var videoTrack = -1
    private val muxerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var drainThread: HandlerThread? = null
    private var drainHandler: Handler? = null
    private var callback: Callback? = null

    fun start(settings: RecordingSettings, cb: Callback) {
        callback = cb
        startThreads()
        handler?.post { startOnCameraThread(settings) }
    }

    private fun startOnCameraThread(settings: RecordingSettings) {
        runCatching {
            val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val fps = settings.fps.toIntOrNull() ?: 30
            val size = sizeFor(settings.resolution)
            val cameraId = settings.cameraId
                ?: findBackCamera(manager, size, fps)

            // 1. Crear el encoder AV1
            val format = MediaFormat.createVideoFormat(MIME_AV1, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRateFor(size, fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= 26) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8)
                }
            }

            val enc = MediaCodec.createEncoderByType(MIME_AV1)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = enc.createInputSurface()
            encoder = enc

            // 2. Crear MediaStore URI
            val uri = createOutputUri()
            val fd = appContext.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("No se pudo abrir FileDescriptor")
            outputUri = uri
            pfd = fd

            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            enc.start()

            // 3. Iniciar drenaje asíncrono del encoder
            startDrainLoop()

            // 4. Abrir cámara y apuntar a la Surface del encoder
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    createCameraSession(device, fps)
                }
                override fun onDisconnected(device: CameraDevice) {
                    dispatchError("Cámara desconectada")
                    cleanup()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    dispatchError("Camera error $error")
                    cleanup()
                }
            }, handler)

        }.onFailure { e ->
            dispatchError(e.message ?: e.toString())
            cleanup()
        }
    }

    private fun createCameraSession(device: CameraDevice, fps: Int) {
        val surface = encoderSurface ?: run { dispatchError("Sin surface"); return }
        runCatching {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                    }.build()
                    sess.setRepeatingRequest(request, null, handler)
                    callback?.onStarted()
                    Log.i(TAG, "AV1 grabando: ${fps}fps")
                }
                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    dispatchError("Sesión Camera2 AV1 falló")
                    cleanup()
                }
            }, handler)
        }.onFailure { e -> dispatchError(e.message ?: e.toString()); cleanup() }
    }

    private fun startDrainLoop() {
        val enc = encoder ?: return
        val mux = muxer ?: return
        drainHandler?.post {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted.get()) {
                            videoTrack = mux.addTrack(enc.outputFormat)
                            mux.start()
                            muxerStarted.set(true)
                        }
                    }
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)
                        if (buf != null && info.size > 0
                            && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            && muxerStarted.get()) {
                            mux.writeSampleData(videoTrack, buf, info)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> break
                }
            }
        }
    }

    fun stop() {
        handler?.post {
            val uri = outputUri
            runCatching { encoder?.signalEndOfInputStream() }
            // Esperar que el drainLoop termine
            drainThread?.join(3000)
            cleanup(closeCallback = false)
            callback?.onFinalized(uri?.let { readActualVideoInfo(appContext, it) })
            callback = null
        }
    }

    fun forceRelease() { runCatching { cleanup() } }

    private fun cleanup(closeCallback: Boolean = true) {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { if (muxerStarted.getAndSet(false)) muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { encoderSurface?.release() }
        runCatching { pfd?.close() }
        runCatching { thread?.quitSafely() }
        runCatching { drainThread?.quitSafely() }
        // Marcar IS_PENDING = 0 en MediaStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputUri?.let { uri ->
                runCatching {
                    appContext.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null, null
                    )
                }
            }
        }
        session = null; camera = null; encoder = null
        encoderSurface = null; muxer = null; pfd = null
        thread = null; handler = null; drainThread = null; drainHandler = null
        if (closeCallback) callback = null
    }

    private fun startThreads() {
        thread = HandlerThread("AV1-Camera").also { it.start() }
        handler = Handler(thread!!.looper)
        drainThread = HandlerThread("AV1-Drain").also { it.start() }
        drainHandler = Handler(drainThread!!.looper)
    }

    private fun dispatchError(msg: String) {
        Log.e(TAG, msg)
        callback?.onError(msg)
    }

    private fun createOutputUri(): Uri {
        val name = "LogiQD_AV1_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LogiQD CineCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        return appContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("No se pudo crear MediaStore Uri")
    }

    private fun sizeFor(resolution: String) = when (resolution) {
        "4K UHD" -> android.util.Size(3840, 2160)
        "1080p"  -> android.util.Size(1920, 1080)
        "720p"   -> android.util.Size(1280, 720)
        else     -> android.util.Size(1920, 1080)
    }

    private fun bitRateFor(size: android.util.Size, fps: Int): Int {
        val pixels = size.width * size.height
        val base = when {
            pixels >= 3840 * 2160 -> 15_000_000  // AV1 4K: 15 Mbps vs 90 Mbps H.265
            pixels >= 1920 * 1080 -> 6_000_000   // AV1 1080p: 6 Mbps vs 35 Mbps H.265
            else                  -> 3_000_000
        }
        return (base * (fps / 30f)).toInt()
    }

    private fun findBackCamera(manager: CameraManager, size: android.util.Size, fps: Int): String {
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: error("Sin cámara trasera")
    }
}
```

### Paso 3 — Integrar en `CameraRepository`

```kotlin
class CameraRepository(private val context: Context) {
    // ...
    private val camera2Av1Recorder = Camera2Av1Recorder(appContext)
    private var activeAv1Recording = false

    fun startRecording() {
        val settings = _state.value.recordingSettings
        if (settings.codec == "AV1") {
            startAv1Recording()  // ← nueva ruta
            return
        }
        // ... resto igual
    }

    private fun startAv1Recording() {
        val settings = _state.value.recordingSettings
        runCatching { cameraProvider?.unbindAll() }
        camera = null; videoCapture = null; lastBindKey = null

        camera2Av1Recorder.start(settings, object : Camera2Av1Recorder.Callback {
            override fun onStarted() {
                activeAv1Recording = true
                mainExecutor.execute {
                    _state.update { it.copy(isRecording = true, message = "Grabando AV1") }
                }
            }
            override fun onError(message: String) {
                activeAv1Recording = false
                mainExecutor.execute {
                    _state.update { it.copy(isRecording = false, message = "Error AV1: $message", isError = true) }
                    rebindCameraXPreview()
                }
            }
            override fun onFinalized(actualInfo: String?) {
                activeAv1Recording = false
                mainExecutor.execute {
                    _state.update { it.copy(isRecording = false, message = "Guardado AV1 ${actualInfo ?: ""}".trim()) }
                    rebindCameraXPreview()
                }
            }
        })
    }

    fun stopRecording() {
        if (activeAv1Recording) {
            camera2Av1Recorder.stop()
            return
        }
        // ... resto igual
    }
}
```

---

## Tests sugeridos

- Galaxy S23 Ultra Android 14 → AV1 4K 30fps → el archivo debe existir en galería con metadata correcta.
- Comparar tamaño: AV1 4K 30fps 60s vs H.265 4K 30fps 60s → AV1 debe ser ~40% menor.
- Cancelar grabación a los 3s → el archivo debe estar en galería y reproducirse correctamente.
- Rotar durante grabación AV1 → sin crash.
