# MEJORA-06 — HDR10 y Dolby Vision en grabación Camera2

| Campo | Valor |
|---|---|
| Prioridad | Media |
| Tipo | Backend |
| Esfuerzo estimado | 1-2 días |

---

## Descripción

El selector de rango dinámico muestra HDR10 y Dolby Vision, pero el código solo implementa HLG10. Completar la implementación para Camera2 usando `DynamicRangeProfiles`.

---

## Contexto técnico

| Perfil | Descripción | Soporte en Samsung |
|---|---|---|
| SDR | Standard Dynamic Range | Todos |
| HLG10 | Hybrid Log Gamma 10-bit | S21+, S22+, S23, S24 |
| HDR10 | HDR10 10-bit, metadata estática | S22+, S23, S24 |
| HDR10+ | HDR10+ con metadata dinámica | S23 Ultra, S24 Ultra |
| Dolby Vision | Dolby Vision 10-bit | Solo algunos modelos |

---

## Implementación

### Paso 1 — Actualizar `RecordingSettings` para incluir perfil de 10 bits

Los perfiles HDR10 requieren codificar en 10 bits. Añadir configuración de bit depth:

```kotlin
data class RecordingSettings(
    // ...
    val dynamicRange: String = "SDR",
    val use10BitProfile: Boolean = false,  // ← AÑADIR
)
```

### Paso 2 — Configurar `MediaRecorder` para 10 bits

```kotlin
// En Camera2HighSpeedRecorder.buildRecorder():
fun buildRecorder(...): MediaRecorder {
    return MediaRecorder().apply {
        // ...
        if (settings.use10BitProfile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Para HDR10/HLG10: usar perfil HEVC que soporte 10 bits
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            // Intentar configurar perfil Main10 via reflection si la API no lo expone directamente
        }
        setVideoEncoder(
            when {
                settings.codec == "H.265/HEVC" -> MediaRecorder.VideoEncoder.HEVC
                else -> MediaRecorder.VideoEncoder.H264
            }
        )
    }
}
```

### Paso 3 — Configurar el rango dinámico en la sesión Camera2

Camera2 gestiona el rango dinámico a nivel de `CaptureRequest`:

```kotlin
// En Camera2HighSpeedRecorder.createRecordingSession():
private fun createRecordingSession(device: CameraDevice, ...) {
    // ...
    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
        addTarget(recorderSurface)
        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))

        // Configurar rango dinámico para API 33+
        if (Build.VERSION.SDK_INT >= 33) {
            val dynamicRangeRequest = when (settings.dynamicRange) {
                "HLG10" -> android.hardware.camera2.params.DynamicRangeProfiles.HLG10
                "HDR10" -> android.hardware.camera2.params.DynamicRangeProfiles.HDR10
                "HDR10+" -> android.hardware.camera2.params.DynamicRangeProfiles.HDR10_PLUS
                "Dolby Vision" -> android.hardware.camera2.params.DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF
                else -> android.hardware.camera2.params.DynamicRangeProfiles.STANDARD
            }
            set(CaptureRequest.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP, dynamicRangeRequest)
        }
    }.build()
}
```

### Paso 4 — Actualizar `buildAvailableDynamicRanges()` en el ViewModel

```kotlin
private fun buildAvailableDynamicRanges(cameras: List<AdvancedCameraInfo>): List<String> {
    val ranges = cameras
        .filter { it.lensFacing == "BACK" }
        .flatMap { it.dynamicRangeProfiles }
        .toSet()
    return buildList {
        add("SDR")
        if (ranges.any { it.contains("HLG10") }) add("HLG10")
        if (ranges.any { it == "HDR10" }) add("HDR10")
        if (ranges.any { it == "HDR10+" }) add("HDR10+")
        if (ranges.any { it.contains("Dolby") }) add("Dolby Vision")
    }
}
```

---

## Tests sugeridos

- Samsung S23 Ultra → seleccionar HLG10 → grabar → abrir en editor HDR → metadata HLG10 presente.
- Samsung S24 Ultra → seleccionar HDR10+ → grabar → verificar metadata con `mediainfo`.
- Dispositivo sin soporte HDR → los perfiles no SDR no deben aparecer en el selector.
