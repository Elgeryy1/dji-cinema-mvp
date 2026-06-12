# MEJORA-09 — Slider de zoom continuo en lugar de botones +/-

| Campo | Valor |
|---|---|
| Prioridad | Alta |
| Tipo | Frontend |
| Esfuerzo estimado | 2 horas |

---

## Descripción

Reemplazar los botones discretos "Z+" y "Z-" por un `Slider` de Compose con movimiento continuo y feedback háptico. El slider muestra el ratio actual ("2.4×") en tiempo real.

---

## Implementación

### Paso 1 — Crear el composable `ZoomSlider`

```kotlin
@Composable
private fun ZoomSlider(
    zoomRatio: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mostrar ratio actual al arrastrar
        AnimatedVisibility(visible = isDragging) {
            Text(
                "${"%.1f".format(zoomRatio)}×",
                color = Accent,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(Modifier.height(4.dp))

        if (vertical) {
            // Slider vertical para landscape
            Box(modifier = Modifier.height(160.dp).width(36.dp)) {
                Slider(
                    value = zoomRatio,
                    onValueChange = { newRatio ->
                        if (!isDragging) isDragging = true
                        onZoomChange(newRatio)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onValueChangeFinished = { isDragging = false },
                    valueRange = minZoom..maxZoom,
                    modifier = Modifier
                        .graphicsLayer {
                            rotationZ = -90f
                            translationX = -(160.dp.toPx() / 2 - 18.dp.toPx())
                        }
                        .width(160.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Stroke
                    )
                )
            }
        } else {
            // Slider horizontal para portrait
            Slider(
                value = zoomRatio,
                onValueChange = { newRatio ->
                    isDragging = true
                    onZoomChange(newRatio)
                },
                onValueChangeFinished = { isDragging = false },
                valueRange = minZoom..maxZoom,
                modifier = Modifier.width(120.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Stroke
                )
            )
        }

        // Etiquetas min/max
        Row(Modifier.fillMaxWidth(0.8f), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${"%.1f".format(minZoom)}×", color = TextMuted, style = MaterialTheme.typography.labelSmall)
            Text("${"%.1f".format(maxZoom)}×", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
```

### Paso 2 — Exponer `setZoomRatio` directamente en el ViewModel

```kotlin
// En MainViewModel.kt — añadir:
fun setZoomRatio(ratio: Float) = cameraRepository.setZoomRatio(ratio)
```

### Paso 3 — Reemplazar en `RightRail` (landscape)

```kotlin
@Composable
private fun RightRail(uiState: AppUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, ...) {
        Button(onClick = viewModel::toggleRecording, ...) { ... }
        Spacer(Modifier.height(14.dp))
        // ANTES: SmallButton("Z+", ...) + SmallButton("Z-", ...)
        // DESPUÉS:
        ZoomSlider(
            zoomRatio = uiState.zoomRatio,
            minZoom = uiState.minZoomRatio,
            maxZoom = uiState.maxZoomRatio,
            onZoomChange = viewModel::setZoomRatio,
            vertical = true
        )
    }
}
```

### Paso 4 — Añadir en `PortraitCameraUi` (portrait)

```kotlin
@Composable
private fun PortraitCameraUi(...) {
    CameraStage(uiState, viewModel) {
        TopHud(...)
        BottomStrip(...)
        // Botón REC a la derecha
        Button(onClick = viewModel::toggleRecording,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(82.dp), ...)
        // Slider de zoom arriba del botón REC
        ZoomSlider(
            zoomRatio = uiState.zoomRatio,
            minZoom = uiState.minZoomRatio,
            maxZoom = uiState.maxZoomRatio,
            onZoomChange = viewModel::setZoomRatio,
            vertical = false,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp, bottom = 100.dp)
        )
    }
}
```

---

## Tests sugeridos

- Arrastrar el slider al máximo → el zoom debe llegar al `maxZoomRatio` del dispositivo.
- Soltar el slider → la cámara debe mantener el zoom.
- Durante grabación activa → el slider debe seguir funcionando sin interrumpir la grabación.
- Control DJI (ZoomIn/ZoomOut) → el slider debe moverse visualmente al recibir eventos del gimbal.
