# MEJORA-15 — Controles de zoom en modo portrait

| Campo | Valor |
|---|---|
| Prioridad | Media |
| Tipo | Frontend |
| Esfuerzo estimado | 1 hora |

---

## Descripción

La vista portrait tiene el botón REC en el centro-derecha pero no expone controles de zoom. En landscape el `RightRail` tiene Z+ y Z-, pero en portrait no hay ningún control de zoom. Añadir el slider de zoom también en portrait.

---

## Implementación

Ver código del `ZoomSlider` en [MEJORA-09](MEJORA-09-slider-zoom.md).

```kotlin
@Composable
private fun PortraitCameraUi(
    uiState: AppUiState,
    viewModel: MainViewModel,
    requestPermissions: (Array<String>) -> Unit
) {
    CameraStage(uiState, viewModel) {
        TopHud(uiState, Modifier.align(Alignment.TopCenter).padding(12.dp))
        BottomStrip(uiState = uiState, viewModel = viewModel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))

        // Columna derecha: REC + Zoom
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = viewModel::toggleRecording,
                modifier = Modifier.size(82.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isRecording) Color(0xFF7C1513) else RecRed
                )
            ) {
                Text(
                    if (uiState.isRecording) "STOP" else "REC",
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )
            }

            // Slider de zoom (vertical, compacto)
            ZoomSlider(
                zoomRatio = uiState.zoomRatio,
                minZoom = uiState.minZoomRatio,
                maxZoom = uiState.maxZoomRatio,
                onZoomChange = viewModel::setZoomRatio,
                vertical = true,
                modifier = Modifier.height(100.dp)
            )
        }
    }
}
```

---

## Tests sugeridos

- En portrait: arrastrar el slider de zoom → el preview debe hacer zoom correctamente.
- En portrait durante grabación → el zoom debe funcionar sin interrumpir la grabación.
- Botón REC aún debe ser accesible y no solaparse con el slider.
