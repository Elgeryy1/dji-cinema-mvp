# MEJORA-04 — Timeout cancelable con coroutines

| Campo | Valor |
|---|---|
| Prioridad | Alta |
| Tipo | Backend |
| Esfuerzo estimado | 1 hora |

---

## Descripción

Reemplazar el `postDelayed` de 5 segundos que actúa como watchdog de grabación por un `Job` de coroutine cancelable. El watchdog se cancela automáticamente cuando `onStarted()` o `onError()` confirman el resultado.

> Ver BUG-02 para la descripción completa del problema.

---

## Implementación

```kotlin
// En CameraRepository.kt:
private var recordingWatchdog: Runnable? = null

// En startCamera2Recording() — reemplazar el postDelayed anónimo:
val watchdog = Runnable {
    val current = _state.value
    if (!current.isRecording && current.message.startsWith("Arrancando Camera2")) {
        camera2HighSpeedRecorder.forceRelease()
        _state.update {
            it.copy(
                isRecording = false,
                message = "Error Camera2: timeout 5s",
                isError = true
            )
        }
        rebindCameraXPreview()
    }
}
recordingWatchdog = watchdog
mainHandler.postDelayed(watchdog, 5000L)

// En cada callback de Camera2HighSpeedRecorder — cancelar watchdog:
override fun onStarted() {
    recordingWatchdog?.let { mainHandler.removeCallbacks(it) }
    recordingWatchdog = null
    // ...
}
override fun onError(message: String) {
    recordingWatchdog?.let { mainHandler.removeCallbacks(it) }
    recordingWatchdog = null
    // ...
}
override fun onFinalized(actualInfo: String?) {
    recordingWatchdog?.let { mainHandler.removeCallbacks(it) }
    recordingWatchdog = null
    // ...
}
```

---

## Tests sugeridos

- Grabar 2s y parar → a los 5s el mensaje de la UI no debe cambiar a "timeout".
- Simular error en cámara → watchdog debe ser cancelado por `onError`, no disparar él mismo.
