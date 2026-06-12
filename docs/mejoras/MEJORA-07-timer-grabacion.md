# MEJORA-07 — Timer de duración de grabación

| Campo | Valor |
|---|---|
| Prioridad | Media |
| Tipo | Backend + Frontend |
| Esfuerzo estimado | 1 hora |

---

## Descripción

Mostrar el tiempo transcurrido de grabación en la HUD con formato `HH:MM:SS` y animación de parpadeo en el punto rojo.

---

## Implementación Backend

### Paso 1 — Añadir `recordingElapsedMs` a `CameraState`

```kotlin
data class CameraState(
    // ...
    val recordingElapsedMs: Long = 0L,
)
```

### Paso 2 — Tick en `CameraRepository`

```kotlin
private var timerJob: kotlinx.coroutines.Job? = null
private val timerScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
)

// En onStarted del callback Camera2:
timerJob?.cancel()
_state.update { it.copy(recordingElapsedMs = 0L) }
timerJob = timerScope.launch {
    while (true) {
        delay(100L)
        _state.update { it.copy(recordingElapsedMs = it.recordingElapsedMs + 100L) }
    }
}

// En onFinalized y onError:
timerJob?.cancel()
timerJob = null
```

### Paso 3 — Exponer en `AppUiState`

```kotlin
data class AppUiState(
    // ...
    val recordingElapsedMs: Long = 0L,
)

// En el combine del ViewModel:
recordingElapsedMs = camera.recordingElapsedMs,
```

---

## Implementación Frontend

### Paso 4 — Mostrar el timer en `TopHud`

```kotlin
@Composable
private fun TopHud(uiState: AppUiState, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().panelBg(), ...) {
        Text("LogiQD CineCam", ...)
        Spacer(Modifier.width(12.dp))

        if (uiState.isRecording) {
            RecordingDot()  // punto rojo parpadeante
            Spacer(Modifier.width(6.dp))
            Text(
                formatElapsed(uiState.recordingElapsedMs),
                color = RecRed,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium
            )
        } else {
            StatusPill("STBY", TextMuted)
        }
        // ...
    }
}

@Composable
private fun RecordingDot() {
    val inf = rememberInfiniteTransition(label = "rec")
    val alpha by inf.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "recAlpha"
    )
    Box(
        Modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(RecRed)
    )
}

private fun formatElapsed(ms: Long): String {
    val totalSecs = ms / 1000
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
           else "%02d:%02d".format(m, s)
}
```

---

## Tests sugeridos

- Iniciar grabación → el timer debe comenzar en 00:00 y avanzar cada segundo.
- Detener grabación → el timer debe congelarse hasta la siguiente grabación.
- Grabación de más de 1 hora → el formato debe cambiar a H:MM:SS.
- Rotar dispositivo durante grabación → el timer no debe reiniciarse.
