# BUG-02 — Race condition en el timeout de inicio Camera2

| Campo | Valor |
|---|---|
| Severidad | CRÍTICO |
| Archivo | `CameraRepository.kt` |
| Líneas afectadas | ~218-231 (bloque `mainHandler.postDelayed`) |
| Esfuerzo estimado | 1 hora |

---

## Descripción

En `startCamera2Recording()` se lanza un `postDelayed` de 5 segundos como watchdog:

```kotlin
mainHandler.postDelayed({
    val current = _state.value
    if (!current.isRecording && current.message.startsWith("Arrancando Camera2")) {
        camera2HighSpeedRecorder.forceRelease()
        _state.update { ... }
        rebindCameraXPreview()
    }
}, 5000L)
```

**El problema:** este handler **nunca se cancela**, incluso cuando la grabación arranca correctamente. Si la grabación empieza pero falla o se detiene dentro de los 5 segundos (por ejemplo: el usuario para la grabación a los 2s, o hay un error de Media), el watchdog dispara `forceRelease()` sobre recursos ya liberados.

---

## Causa raíz

El `Runnable` del `postDelayed` no se guarda en ninguna variable, por lo que es imposible cancelarlo con `mainHandler.removeCallbacks(...)`.

---

## Impacto

- **Crash potencial**: `forceRelease()` llama a `cleanup()` que cierra `session`, `camera`, `recorder`. Si ya fueron cerrados, varios de estos lanzarán excepciones silenciosas o estados corruptos.
- **Estado inconsistente**: después de una grabación correcta de 3 segundos, a los 5s el watchdog resetea el mensaje de la UI a "Error Camera2: timeout arrancando..."
- **Doble `rebindCameraXPreview()`**: se llama desde `onError`/`onFinalized` Y desde el watchdog, causando un doble bind de la cámara.

---

## Cómo reproducirlo

1. Iniciar grabación a 1080p 30fps (arranca en < 2s).
2. Parar la grabación inmediatamente (< 1s).
3. Esperar 5 segundos.
4. Observar en Logcat: el watchdog dispara `forceRelease()` y actualiza el mensaje de la UI aunque la grabación ya terminó correctamente.

---

## Solución paso a paso

### Paso 1 — Añadir un campo para el Job del watchdog

En `CameraRepository.kt`, añadir junto a los otros campos privados:

```kotlin
private var recordingWatchdogJob: kotlinx.coroutines.Job? = null
```

> **Nota:** `CameraRepository` no tiene un `CoroutineScope` propio porque no es un `ViewModel`. Hay dos opciones: (A) pasar el scope desde el ViewModel, o (B) mantener el `mainHandler` pero guardar el `Runnable`. La opción B es más rápida y menos invasiva.

### Paso 2 — Guardar el Runnable para poder cancelarlo

```kotlin
// ANTES (problemático):
mainHandler.postDelayed({
    val current = _state.value
    if (!current.isRecording && current.message.startsWith("Arrancando Camera2")) {
        camera2HighSpeedRecorder.forceRelease()
        _state.update { it.copy(...) }
        rebindCameraXPreview()
    }
}, 5000L)

// DESPUÉS (correcto):
val watchdog = Runnable {
    val current = _state.value
    if (!current.isRecording && current.message.startsWith("Arrancando Camera2")) {
        Log.w("CINECAM_CAMERA", "Watchdog: timeout 5s sin confirmación de grabación")
        camera2HighSpeedRecorder.forceRelease()
        _state.update {
            it.copy(
                isRecording = false,
                message = "Error Camera2: timeout arrancando ${settings.resolution} ${settings.fps}fps ${settings.codec}",
                appliedRecordingMode = "Camera2 timeout: ${settings.summary}"
            )
        }
        rebindCameraXPreview()
    }
}
recordingWatchdog = watchdog
mainHandler.postDelayed(watchdog, 5000L)
```

Añadir el campo:

```kotlin
private var recordingWatchdog: Runnable? = null
```

### Paso 3 — Cancelar el watchdog en `onStarted()` y `onError()`

```kotlin
camera2HighSpeedRecorder.start(settings, object : Camera2HighSpeedRecorder.Callback {
    override fun onStarted() {
        recordingWatchdog?.let { mainHandler.removeCallbacks(it) }  // ← AÑADIR
        recordingWatchdog = null
        activeCamera2Recording = true
        mainExecutor.execute {
            _state.update { it.copy(isRecording = true, ...) }
        }
    }

    override fun onError(message: String) {
        recordingWatchdog?.let { mainHandler.removeCallbacks(it) }  // ← AÑADIR
        recordingWatchdog = null
        activeCamera2Recording = false
        mainExecutor.execute {
            _state.update { it.copy(isRecording = false, ...) }
            rebindCameraXPreview()
        }
    }

    override fun onFinalized(actualInfo: String?) {
        recordingWatchdog?.let { mainHandler.removeCallbacks(it) }  // ← AÑADIR
        recordingWatchdog = null
        activeCamera2Recording = false
        mainExecutor.execute {
            _state.update { it.copy(isRecording = false, ...) }
            rebindCameraXPreview()
        }
    }
})
```

### Paso 4 — Cancelar también en `stopRecording()`

```kotlin
fun stopRecording() {
    recordingWatchdog?.let { mainHandler.removeCallbacks(it) }  // ← AÑADIR
    recordingWatchdog = null
    if (activeCamera2Recording) {
        _state.update { it.copy(message = "Deteniendo Camera2") }
        camera2HighSpeedRecorder.stop()
        return
    }
    // ...
}
```

---

## Tests sugeridos

1. **Happy path corto**: iniciar grabación, parar a los 2s, esperar 6s → la UI no debe mostrar ningún mensaje de timeout.
2. **Timeout real**: simular fallo desconectando la cámara durante el inicio → el watchdog debe dispararse exactamente una vez a los 5s.
3. **Error rápido**: configurar un codec inválido que falle inmediatamente → el watchdog no debe dispararse, el error de `onError()` es suficiente.
