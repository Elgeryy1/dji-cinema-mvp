# BUG-06 — AV1 codec seleccionable pero completamente no funcional

| Campo | Valor |
|---|---|
| Severidad | MEDIO |
| Archivos | `CameraRepository.kt` (~121), `CineCamScreen.kt` (OptionRow de codec) |
| Esfuerzo estimado | 1 hora (fix UX) / 1-2 semanas (implementación completa) |

---

## Descripción

El usuario puede seleccionar AV1 en el picker de codecs de la UI. Al intentar grabar con AV1 activo, `CameraRepository.startRecording()` rechaza la grabación con:

```
"Error Camera2: AV1 requiere grabador MediaCodec; MediaRecorder de Samsung no arranca AV1 aquí"
```

La opción AV1 existe en el selector pero **no tiene implementación funcional**. El código en `startRecording()`:

```kotlin
if (settings.codec == "AV1") {
    _state.update {
        it.copy(
            isRecording = false,
            message = "Error Camera2: AV1 requiere grabador MediaCodec...",
            appliedRecordingMode = "AV1 pendiente de ruta MediaCodec/Muxer"
        )
    }
    return
}
```

---

## Causa raíz

AV1 fue planeado pero nunca implementado. La opción se dejó en el selector de codecs con la intención de implementarla más tarde, sin marcarla como no disponible en la UI.

---

## Impacto

- El usuario selecciona AV1, intenta grabar, y recibe un mensaje de error críptico.
- No hay feedback preventivo antes de pulsar REC.
- El usuario no entiende si AV1 "no está soportado por su dispositivo" o "es un bug de la app".

---

## Solución — Fix de UX inmediato (30 minutos)

### Paso 1 — Exponer `hasAv1SurfaceEncoder()` al ViewModel

`hasAv1SurfaceEncoder()` actualmente es `private` en `Camera2HighSpeedRecorder`. Hacerla accesible:

```kotlin
// En Camera2HighSpeedRecorder.kt — cambiar de private a internal:
internal fun hasAv1SurfaceEncoder(): Boolean { ... }

// O extraerla a VideoInfoUtils.kt como función top-level:
fun hasAv1SurfaceEncoder(): Boolean {
    return runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder &&
            codec.supportedTypes.any { it.equals("video/av01", ignoreCase = true) } &&
            runCatching {
                codec.getCapabilitiesForType("video/av01").colorFormats
                    .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }.getOrDefault(false)
        }
    }.getOrDefault(false)
}
```

### Paso 2 — Exponer `av1Supported` en `AppUiState`

```kotlin
// En AppUiState.kt:
data class AppUiState(
    // ...
    val av1Supported: Boolean = false,
    // ...
)

// En MainViewModel.kt, en el combine:
AppUiState(
    // ...
    availableCodecs = buildList {
        if ("video/hevc" in mimes) add("H.265/HEVC")
        if ("video/avc" in mimes) add("H.264/AVC")
        add("AV1")  // siempre mostrar, pero marcar si no soportado
    },
    av1Supported = hasAv1SurfaceEncoder(),
    // ...
)
```

### Paso 3 — Modificar `OptionRow` en `CineCamScreen.kt` para deshabilitar AV1

```kotlin
// Crear un OptionRowWithDisabled que acepte opciones con estado enabled/disabled:
@Composable
private fun OptionRow(
    options: List<String>,
    selected: String,
    disabledOptions: Set<String> = emptySet(),
    onSelected: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        options.forEach { option ->
            val isDisabled = option in disabledOptions
            val isSelected = option == selected && !isDisabled
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        when {
                            isSelected  -> Accent
                            isDisabled  -> Color(0xFF2A2A2A)
                            else        -> Color.Transparent
                        }
                    )
                    .border(
                        BorderStroke(1.dp, if (isDisabled) Color(0xFF444444) else if (isSelected) Accent else Stroke),
                        RoundedCornerShape(7.dp)
                    )
                    .clickable(enabled = !isDisabled) { onSelected(option) }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        option,
                        color = when {
                            isSelected -> Color.Black
                            isDisabled -> Color(0xFF555555)
                            else       -> TextPrimary
                        },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    if (isDisabled) {
                        Text(
                            "(no soportado)",
                            color = Color(0xFF555555),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

// En el MenuPanel.Codec:
MenuPanel.Codec -> {
    OptionRow(
        options = uiState.availableCodecs,
        selected = uiState.selectedCodec,
        disabledOptions = if (!uiState.av1Supported) setOf("AV1") else emptySet()
    ) { viewModel.setCodec(it) }
}
```

---

## Tests sugeridos

- En dispositivo sin AV1 encoder → la opción AV1 debe aparecer gris con "(no soportado)", no clickable.
- En dispositivo con AV1 encoder (Samsung S23/S24 con Android 14+) → AV1 debe ser seleccionable y grabar (requiere implementación completa del BUG-06 avanzado).
- Seleccionar AV1 deshabilitado → no debe cambiar el codec seleccionado.

---

## Implementación completa de AV1 (ver MEJORA-01)

Ver [MEJORA-01-pipeline-av1-mediacodec.md](../mejoras/MEJORA-01-pipeline-av1-mediacodec.md) para la implementación completa de la pipeline AV1 via MediaCodec + MediaMuxer.
