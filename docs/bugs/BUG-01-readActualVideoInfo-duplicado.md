# BUG-01 — `readActualVideoInfo` duplicado (código muerto)

| Campo | Valor |
|---|---|
| Severidad | CRÍTICO |
| Archivos | `CameraRepository.kt`, `Camera2HighSpeedRecorder.kt` |
| Líneas afectadas | `CameraRepository.kt:438-455` |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

El método `readActualVideoInfo(uri: Uri): String?` está implementado de forma **idéntica** en dos clases distintas:

- `Camera2HighSpeedRecorder.kt` — versión que **sí se invoca** desde `stop()`
- `CameraRepository.kt` — copia **muerta** que nunca se llama

Cualquier bug corregido en una no se propaga a la otra. Si se añaden funcionalidades (e.g., leer bitrate, duración), hay que hacerlo dos veces.

---

## Causa raíz

Durante el desarrollo se copió el método a `CameraRepository` para un uso que quedó obsoleto cuando toda la grabación migró a `Camera2HighSpeedRecorder`. La copia en `CameraRepository` quedó huérfana.

---

## Impacto

- Código difícil de mantener: dos fuentes de verdad para la misma lógica.
- Si se añade lectura de bitrate o duración en una clase, la otra quedará desactualizada silenciosamente.
- Confusión para nuevos desarrolladores sobre cuál versión es la "oficial".

---

## Cómo reproducirlo

Buscar en el proyecto todos los usos de `readActualVideoInfo`:

```
grep -r "readActualVideoInfo" app/src/
```

Resultado esperado (incorrecto):
```
Camera2HighSpeedRecorder.kt:307: private fun readActualVideoInfo(uri: Uri): String?
CameraRepository.kt:438:          private fun readActualVideoInfo(uri: android.net.Uri): String?
```

Solo `Camera2HighSpeedRecorder.kt` invoca el método. En `CameraRepository` nadie la llama.

---

## Solución paso a paso

### Paso 1 — Crear un fichero de utilidades

Crear `app/src/main/java/com/cinemaapp/djimvp/camera/VideoInfoUtils.kt`:

```kotlin
package com.cinemaapp.djimvp.camera

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Build
import java.util.Locale

/**
 * Lee los metadatos reales del vídeo después de grabar.
 * Retorna una cadena "WxH fps" o null si no se puede leer.
 */
fun readActualVideoInfo(context: Context, uri: Uri): String? {
    return runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val fps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
        } else null
        retriever.release()
        listOfNotNull(
            width?.let { w -> height?.let { h -> "${w}x${h}" } },
            fps?.toFloatOrNull()?.let { "%.2ffps".format(Locale.US, it) }
        ).joinToString(" ")
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
```

### Paso 2 — Actualizar `Camera2HighSpeedRecorder.kt`

Eliminar el método privado `readActualVideoInfo` de la clase e importar la función de utilidad:

```kotlin
// ELIMINAR este bloque completo de Camera2HighSpeedRecorder.kt:
// private fun readActualVideoInfo(uri: Uri): String? { ... }

// En stop(), cambiar la llamada:
// ANTES:
callback?.onFinalized(actualUri?.let(::readActualVideoInfo))

// DESPUÉS:
callback?.onFinalized(actualUri?.let { readActualVideoInfo(appContext, it) })
```

### Paso 3 — Eliminar la copia muerta de `CameraRepository.kt`

Eliminar líneas 438-455 de `CameraRepository.kt` (el bloque `private fun readActualVideoInfo`).

### Paso 4 — Verificar

```bash
grep -r "readActualVideoInfo" app/src/
# Debe aparecer solo en VideoInfoUtils.kt (definición) y Camera2HighSpeedRecorder.kt (uso)
```

---

## Tests sugeridos

- Grabar 5 segundos a 4K 30fps. El mensaje "Guardado Camera2 3840x2160 30.00fps" debe aparecer en la HUD.
- Grabar a 1080p 120fps. El mensaje debe mostrar "1920x1080 120.00fps".
- Verificar que no hay regresión: el path de `CameraRepository` sigue compilando sin el método eliminado.
