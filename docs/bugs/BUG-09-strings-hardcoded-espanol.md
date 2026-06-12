# BUG-09 — Strings hardcoded en español en `isUserVisibleCameraError`

| Campo | Valor |
|---|---|
| Severidad | BAJO |
| Archivo | `MainViewModel.kt` |
| Líneas afectadas | ~289-295 |
| Esfuerzo estimado | 1 hora |

---

## Descripción

El método `isUserVisibleCameraError()` decide si un mensaje de cámara debe mostrarse al usuario filtrando por palabras clave hardcodeadas en español:

```kotlin
private fun isUserVisibleCameraError(message: String): Boolean {
    return message.startsWith("Error") ||
        message.contains("fallo", ignoreCase = true) ||
        message.contains("no esta", ignoreCase = true) ||
        message.contains("no disponible", ignoreCase = true) ||
        message.contains("AV1", ignoreCase = true)
}
```

Este patrón es frágil porque:
1. Los mensajes de error vienen de `CameraRepository` y `Camera2HighSpeedRecorder`, que están en español, pero si algún día se traducen, el filtrado deja de funcionar.
2. Un mensaje como `"Grabando Camera2"` (que empieza con "G") no se filtra — correcto. Pero `"Camara no lista para zoom"` sí se filtra (contiene "no") — potencialmente incorrecto.
3. El literal `"no esta"` sin tilde detecta `"no esta"` pero no `"no está"`.

---

## Causa raíz

Se usa un filtro de texto libre en lugar de un sistema de tipado para distinguir errores de mensajes informativos.

---

## Impacto

- Si los mensajes se internacionalizan o se cambia un texto, errores reales pueden no mostrarse al usuario.
- Mensajes informativos pueden aparecer como errores si contienen accidentalmente una de las palabras clave.

---

## Solución paso a paso

### Paso 1 — Añadir un campo `isError` al `CameraState`

En lugar de inferir si un mensaje es un error por su texto, marcarlo explícitamente:

```kotlin
// En CameraRepository.kt:
data class CameraState(
    val isReady: Boolean = false,
    val isRecording: Boolean = false,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val recordingSettings: RecordingSettings = RecordingSettings(),
    val appliedRecordingMode: String = "CameraX listo: perfil por defecto",
    val message: String = "Camara sin inicializar",
    val isError: Boolean = false,  // ← AÑADIR
)
```

### Paso 2 — Marcar explícitamente los errores en `CameraRepository`

```kotlin
// Todos los _state.update que sean errores deben incluir isError = true:

// En onError():
_state.update {
    it.copy(
        isRecording = false,
        isReady = false,
        message = "Error Camera2: $message",
        isError = true,  // ← AÑADIR
        appliedRecordingMode = "Camera2 fallo: $message"
    )
}

// En setZoomRatio cuando camera == null:
_state.update { it.copy(message = "Camara no lista para zoom", isError = false) }
// (este NO es un error visible — es informativo)

// En bindCamera exception:
_state.update { it.copy(isReady = false, message = "Error CameraX: ${error.message}", isError = true) }
```

### Paso 3 — Actualizar `CameraState` en `AppUiState`

```kotlin
// En MainViewModel.kt, en el combine:
AppUiState(
    // ...
    cameraMessage = camera.message,
    cameraIsError = camera.isError,  // ← AÑADIR a AppUiState también
    // ...
    errorMessage = dji.errorMessage
        ?: advanced.error
        ?: camera.message.takeIf { camera.isError }  // ← usar el flag en lugar del regex
)
```

### Paso 4 — Eliminar `isUserVisibleCameraError()`

```kotlin
// ELIMINAR este método de MainViewModel.kt:
private fun isUserVisibleCameraError(message: String): Boolean { ... }
```

### Paso 5 — Añadir `cameraIsError` a `AppUiState`

```kotlin
data class AppUiState(
    // ...
    val cameraIsError: Boolean = false,
    // ...
)
```

---

## Tests sugeridos

- Error real de cámara (codec inválido) → debe mostrarse en `ErrorBanner`.
- Mensaje informativo "Zoom 2.40x" → **no** debe mostrarse en `ErrorBanner`.
- Mensaje "Grabando Camera2" → **no** debe mostrarse en `ErrorBanner`.
- Cambiar el texto de un mensaje de error → el `ErrorBanner` sigue apareciendo correctamente.
