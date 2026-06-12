# MEJORA-14 — Indicador de espacio de almacenamiento

| Campo | Valor |
|---|---|
| Prioridad | Media |
| Tipo | Frontend |
| Esfuerzo estimado | 2 horas |

---

## Descripción

Mostrar el espacio libre disponible y estimar la duración máxima de grabación según el bitrate seleccionado en el panel de Settings.

---

## Implementación

### Paso 1 — Calcular espacio disponible en el ViewModel

```kotlin
// En MainViewModel.kt:
fun getStorageInfo(): StorageInfo {
    val stat = android.os.StatFs(
        android.os.Environment.getExternalStorageDirectory().path
    )
    val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
    val bitrateBytes = estimateBytesPerSecond()
    val maxSeconds = if (bitrateBytes > 0) freeBytes / bitrateBytes else 0L
    return StorageInfo(freeBytes = freeBytes, maxRecordingSeconds = maxSeconds)
}

private fun estimateBytesPerSecond(): Long {
    val settings = cameraRepository.state.value.recordingSettings
    val fps = settings.fps.toIntOrNull() ?: 30
    val pixels = when (settings.resolution) {
        "4K UHD" -> 3840L * 2160
        "1080p"  -> 1920L * 1080
        "720p"   -> 1280L * 720
        else     -> 1920L * 1080
    }
    val baseBitrate = when {
        pixels >= 3840L * 2160 -> 90_000_000L
        pixels >= 1920L * 1080 -> 35_000_000L
        else                   -> 16_000_000L
    }
    val scaledBitrate = (baseBitrate * (fps / 30f)).toLong()
    return scaledBitrate / 8  // bits/s a bytes/s
}

data class StorageInfo(val freeBytes: Long, val maxRecordingSeconds: Long)
```

### Paso 2 — Exponer en `AppUiState`

```kotlin
data class AppUiState(
    // ...
    val storageFreeBytes: Long = 0L,
    val storageMaxRecordingSeconds: Long = 0L,
)
```

### Paso 3 — Mostrar en el panel Settings

```kotlin
@Composable
private fun SettingsMenu(uiState: AppUiState, viewModel: MainViewModel, ...) {
    // ...

    val freeGB = uiState.storageFreeBytes / (1024f * 1024 * 1024)
    val maxMin = uiState.storageMaxRecordingSeconds / 60

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Barra de progreso de almacenamiento
        val totalBytes = ... // StatFs.totalBytes
        val usedFraction = 1f - (uiState.storageFreeBytes.toFloat() / totalBytes)

        LinearProgressIndicator(
            progress = usedFraction,
            modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = when {
                usedFraction > 0.9f -> Color(0xFFE53935)
                usedFraction > 0.7f -> Color(0xFFFFA726)
                else                -> Accent
            }
        )

        Text(
            "%.1f GB libres".format(freeGB),
            color = TextPrimary,
            style = MaterialTheme.typography.labelSmall
        )
    }

    Text(
        "~${maxMin} min grabables con ajustes actuales",
        color = if (maxMin < 5) Color(0xFFE53935) else TextMuted,
        style = MaterialTheme.typography.bodySmall
    )

    // Advertencia si queda muy poco
    if (maxMin < 5) {
        colorBox("Advertencia: menos de 5 minutos de grabación disponibles")
    }
}
```

---

## Tests sugeridos

- Con 64 GB libres y configuración 4K 30fps H.265 → debe mostrar ~95 min disponibles.
- Con poco espacio (< 500 MB) → la barra debe estar en rojo y aparecer la advertencia.
- Cambiar resolución → la estimación debe actualizarse.
