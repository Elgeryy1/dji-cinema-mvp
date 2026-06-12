# MEJORA-05 — Audio configurable (fuente, canales, bitrate)

| Campo | Valor |
|---|---|
| Prioridad | Media |
| Tipo | Backend |
| Esfuerzo estimado | 3 horas |

---

## Descripción

Añadir configuración de audio a `RecordingSettings`: fuente de audio (MIC vs LINE IN), número de canales (estéreo/mono) y bitrate de audio (128k/192k/256k). Actualmente está fijado a MIC estéreo 192k AAC.

---

## Implementación

### Paso 1 — Añadir campos a `RecordingSettings`

```kotlin
data class RecordingSettings(
    val resolution: String = "4K UHD",
    val fps: String = "30",
    val codec: String = "H.265/HEVC",
    val dynamicRange: String = "SDR",
    val pictureProfile: String = "Normal",
    val cameraId: String? = null,
    // ── Audio (nuevos) ───────────────────────────────────────────────────
    val audioSource: AudioSource = AudioSource.MIC,
    val audioChannels: AudioChannels = AudioChannels.STEREO,
    val audioBitrate: Int = 192_000,
) {
    enum class AudioSource { MIC, CAMCORDER, VOICE_RECOGNITION }
    enum class AudioChannels(val count: Int) { MONO(1), STEREO(2) }
}
```

### Paso 2 — Aplicar en `Camera2HighSpeedRecorder.buildRecorder()`

```kotlin
private fun buildRecorder(..., settings: RecordingSettings, ...): MediaRecorder {
    return MediaRecorder().apply {
        // Audio
        setAudioSource(
            when (settings.audioSource) {
                RecordingSettings.AudioSource.MIC -> MediaRecorder.AudioSource.MIC
                RecordingSettings.AudioSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
                RecordingSettings.AudioSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
        )
        setAudioChannels(settings.audioChannels.count)
        setAudioEncodingBitRate(settings.audioBitrate)
        setAudioSamplingRate(48_000)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        // ... resto igual
    }
}
```

### Paso 3 — Añadir al panel Look/Settings en la UI

```kotlin
// En MenuPanel.Look o en un nuevo MenuPanel.Audio:
Text("Fuente de audio", color = TextMuted, style = MaterialTheme.typography.bodySmall)
OptionRow(
    options = listOf("MIC", "CAMCORDER", "VOICE"),
    selected = uiState.selectedAudioSource
) { viewModel.setAudioSource(it) }

Text("Canales", color = TextMuted, style = MaterialTheme.typography.bodySmall)
OptionRow(
    options = listOf("MONO", "STEREO"),
    selected = uiState.selectedAudioChannels
) { viewModel.setAudioChannels(it) }

Text("Bitrate audio", color = TextMuted, style = MaterialTheme.typography.bodySmall)
OptionRow(
    options = listOf("128k", "192k", "256k"),
    selected = "${uiState.selectedAudioBitrate / 1000}k"
) { viewModel.setAudioBitrate(it.replace("k", "").toInt() * 1000) }
```

### Paso 4 — Añadir funciones al ViewModel

```kotlin
fun setAudioSource(value: String) {
    val source = RecordingSettings.AudioSource.entries.find { it.name == value } ?: return
    val current = cameraRepository.state.value.recordingSettings
    cameraRepository.setRecordingSettings(current.copy(audioSource = source))
}

fun setAudioChannels(value: String) {
    val channels = if (value == "MONO") RecordingSettings.AudioChannels.MONO
                   else RecordingSettings.AudioChannels.STEREO
    val current = cameraRepository.state.value.recordingSettings
    cameraRepository.setRecordingSettings(current.copy(audioChannels = channels))
}

fun setAudioBitrate(bitrate: Int) {
    val current = cameraRepository.state.value.recordingSettings
    cameraRepository.setRecordingSettings(current.copy(audioBitrate = bitrate))
}
```

---

## Tests sugeridos

- Grabar en mono → abrir en editor de audio → 1 canal.
- Grabar en estéreo → 2 canales.
- Cambiar bitrate a 256k → el archivo debe ser ~33% más grande en la pista de audio.
- Persistir ajustes de audio → reiniciar → audio config debe mantenerse.
