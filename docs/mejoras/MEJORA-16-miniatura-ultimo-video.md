# MEJORA-16 — Miniatura del último vídeo grabado

| Campo | Valor |
|---|---|
| Prioridad | Baja |
| Tipo | Frontend |
| Esfuerzo estimado | 3 horas |

---

## Descripción

Mostrar en la esquina inferior izquierda una miniatura del último archivo grabado. Al pulsar, abre la galería en ese vídeo.

---

## Implementación

### Paso 1 — Extraer miniatura en `CameraRepository` tras finalizar grabación

```kotlin
// En onFinalized callback — después de obtener actualInfo:
val thumbnail = actualUri?.let { uri ->
    runCatching {
        MediaMetadataRetriever().apply {
            setDataSource(appContext, uri)
        }.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }.getOrNull()
}

_state.update {
    it.copy(
        lastRecordingUri = actualUri,
        lastRecordingThumbnail = thumbnail,
        // ...
    )
}
```

### Paso 2 — Añadir campos a `CameraState` y `AppUiState`

```kotlin
data class CameraState(
    // ...
    val lastRecordingUri: Uri? = null,
    val lastRecordingThumbnail: android.graphics.Bitmap? = null,
)

data class AppUiState(
    // ...
    val lastRecordingUri: String? = null,         // URI como String para la UI
    val lastRecordingThumbnail: ImageBitmap? = null,
)
```

### Paso 3 — Composable `ThumbnailButton`

```kotlin
@Composable
private fun ThumbnailButton(
    thumbnail: ImageBitmap?,
    uri: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    if (thumbnail == null || uri == null) return

    Box(
        modifier
            .size(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(BorderStroke(1.dp, Stroke), RoundedCornerShape(6.dp))
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(uri), "video/mp4")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(intent)
            }
    ) {
        Image(
            bitmap = thumbnail,
            contentDescription = "Último vídeo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Icono play overlay
        Box(
            Modifier.fillMaxSize().background(Color(0x44000000)),
            contentAlignment = Alignment.Center
        ) {
            Text("▶", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// En CameraStage, añadir en la esquina inferior izquierda:
ThumbnailButton(
    thumbnail = uiState.lastRecordingThumbnail,
    uri = uiState.lastRecordingUri,
    modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(start = 12.dp, bottom = 90.dp)
)
```

---

## Tests sugeridos

- Grabar y detener → debe aparecer la miniatura del primer frame del vídeo.
- Pulsar la miniatura → debe abrir la app de galería o reproductor en ese vídeo.
- Primera ejecución (sin vídeos previos) → la miniatura no debe mostrarse.
- La miniatura no debe interferir con el panel inferior.
