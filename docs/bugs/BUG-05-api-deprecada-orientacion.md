# BUG-05 — API deprecada para orientación de vídeo

| Campo | Valor |
|---|---|
| Severidad | ALTO |
| Archivo | `Camera2HighSpeedRecorder.kt` |
| Líneas afectadas | método `orientationHint()` (~349-363) |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

El método `orientationHint()` usa la API deprecada `WindowManager.defaultDisplay` para obtener la rotación actual de la pantalla:

```kotlin
private fun orientationHint(manager: CameraManager, cameraId: String): Int {
    val rotation = runCatching {
        @Suppress("DEPRECATION")
        (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)
    // ...
}
```

`WindowManager.defaultDisplay` está **deprecado desde API 30 (Android 11)** y puede lanzar `NoSuchMethodError` o comportarse de forma inesperada en futuras versiones de Android.

---

## Causa raíz

El código fue escrito cuando API 30+ era la novedad y se añadió `@Suppress("DEPRECATION")` para silenciar el warning sin buscar la alternativa correcta.

---

## Impacto

- Warning de compilación en todas las builds.
- Riesgo de crash en Android 17+ si Google elimina el método.
- En dispositivos con múltiples displays, `defaultDisplay` puede no ser el display correcto.

---

## Solución paso a paso

### Paso 1 — Entender las alternativas disponibles

| API | Disponibilidad | Uso correcto |
|---|---|---|
| `WindowManager.defaultDisplay.rotation` | API 1, **deprecado API 30** | NO usar |
| `context.display?.rotation` | API 30+ | Solo si `minSdk >= 30` |
| `WindowManager.currentWindowMetrics` | API 30+ | Preferido para ventanas |
| `Display.getRotation()` vía `DisplayManager` | API 17+ | Compatible con `minSdk 26+` |

### Paso 2 — Implementar solución compatible (API 26+)

Reemplazar el método `orientationHint()` completo:

```kotlin
private fun orientationHint(manager: CameraManager, cameraId: String): Int {
    val rotation = getDisplayRotation()
    val degrees = when (rotation) {
        Surface.ROTATION_90  -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else                 -> 0
    }
    val sensorOrientation = runCatching {
        manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    }.getOrDefault(90)
    return (sensorOrientation - degrees + 360) % 360
}

private fun getDisplayRotation(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // API 30+: usar el display del contexto de la ventana
        appContext.display?.rotation ?: Surface.ROTATION_0
    } else {
        // API < 30: usar DisplayManager para evitar defaultDisplay
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0
    }
}
```

### Paso 3 — Eliminar el `@Suppress("DEPRECATION")`

Con la nueva implementación ya no es necesario el `@Suppress`. Verificar que no quedan más `@Suppress("DEPRECATION")` relacionados con displays en el archivo.

### Paso 4 — Compilar y verificar

```bash
./gradlew assembleDebug
# No debe aparecer ningún warning de deprecación relacionado con WindowManager.defaultDisplay
```

---

## Tests sugeridos

- Grabar en portrait → el vídeo debe estar orientado correctamente (no rotado 90°).
- Grabar en landscape → el vídeo debe estar orientado correctamente.
- Grabar mientras se rota el dispositivo → el vídeo del clip debe mantener la orientación del inicio de grabación.
- Probar en emulador con API 35 → sin warnings, sin crash.
