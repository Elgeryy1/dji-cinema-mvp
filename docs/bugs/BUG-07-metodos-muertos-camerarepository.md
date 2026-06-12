# BUG-07 — Métodos muertos en CameraRepository

| Campo | Valor |
|---|---|
| Severidad | MEDIO |
| Archivo | `CameraRepository.kt` |
| Líneas afectadas | ~310-335, ~418-476 |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

`CameraRepository` contiene varios métodos privados que **nunca se invocan**. Son restos de una fase anterior en la que se intentó usar CameraX para la grabación. Toda la grabación migró a Camera2, pero el código CameraX quedó huérfano.

### Métodos muertos identificados

| Método | Línea aprox. | Razón de ser muerto |
|---|---|---|
| `qualitySelectorFor(resolution: String)` | ~310 | CameraX `VideoCapture` nunca se usa |
| `frameRateFor(fps: String)` | ~325 | Idem |
| `dynamicRangeFor(dynamicRange: String)` | ~330 | Idem |
| `qualityFor(resolution: String)` | ~429 | Idem |
| `readActualVideoInfo(uri: Uri)` | ~438 | Ver BUG-01 |

---

## Causa raíz

Durante el desarrollo se migró de CameraX `VideoCapture` a `Camera2HighSpeedRecorder`. Se eliminó el código de grabación de CameraX del flujo principal pero se dejaron los helpers como "por si acaso".

---

## Impacto

- ~65 líneas de código muerto que confunden a quien mantiene el código.
- Un desarrollador nuevo puede pensar que `qualitySelectorFor()` se usa en algún path y modificarla sin efecto.
- El método `readActualVideoInfo` duplicado (BUG-01) es parte de este problema.

---

## Cómo detectarlo

Android Studio → Analyze → Inspect Code → buscar "Unused private member".

O en la terminal:
```bash
# Buscar todos los métodos privados de CameraRepository y ver cuáles no se llaman
grep -n "private fun" app/src/main/java/com/cinemaapp/djimvp/camera/CameraRepository.kt
```

---

## Solución paso a paso

### Paso 1 — Eliminar `qualitySelectorFor()`

```kotlin
// ELIMINAR completamente (~líneas 310-323):
private fun qualitySelectorFor(resolution: String): QualitySelector {
    val quality = when (resolution) { ... }
    return QualitySelector.from(quality, ...)
}
```

### Paso 2 — Eliminar `frameRateFor()`

```kotlin
// ELIMINAR completamente (~líneas 325-328):
private fun frameRateFor(fps: String): Range<Int> {
    val value = fps.toIntOrNull() ?: 30
    return Range(value, value)
}
```

### Paso 3 — Eliminar `dynamicRangeFor()`

```kotlin
// ELIMINAR completamente (~líneas 330-335):
private fun dynamicRangeFor(dynamicRange: String): DynamicRange {
    return when (dynamicRange) {
        "HLG10" -> DynamicRange.HLG_10_BIT
        else -> DynamicRange.SDR
    }
}
```

### Paso 4 — Eliminar `qualityFor()`

```kotlin
// ELIMINAR completamente (~líneas 429-435):
private fun qualityFor(resolution: String): Quality {
    return when (resolution) { ... }
}
```

### Paso 5 — Eliminar copia muerta de `readActualVideoInfo()`

```kotlin
// ELIMINAR completamente (~líneas 438-455):
private fun readActualVideoInfo(uri: android.net.Uri): String? { ... }
```

### Paso 6 — Eliminar imports no usados

Android Studio marcará los imports huérfanos:
```kotlin
// ELIMINAR si quedan sin uso:
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.core.DynamicRange
import android.util.Range
```

Verificar con `./gradlew assembleDebug` → 0 warnings de imports no usados.

---

## Tests sugeridos

- `./gradlew assembleDebug` → sin errores de compilación.
- Grabar y detener en todos los modos soportados → ninguna regresión funcional.
- Android Studio Analyze → Inspect Code → 0 "Unused private member" en `CameraRepository`.
