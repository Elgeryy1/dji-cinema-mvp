# BUG-03 — Memory leak: referencias fuertes a LifecycleOwner y PreviewView

| Campo | Valor |
|---|---|
| Severidad | ALTO |
| Archivo | `CameraRepository.kt` |
| Líneas afectadas | campos `lastLifecycleOwner`, `lastPreviewView` (~75-76) |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

`CameraRepository` vive dentro de `MainViewModel`, que **sobrevive a las rotaciones de pantalla**. Sin embargo, guarda referencias directas (fuertes) a objetos de la Activity:

```kotlin
private var lastLifecycleOwner: LifecycleOwner? = null
private var lastPreviewView: PreviewView? = null
```

Cuando el usuario rota el dispositivo:
1. Android destruye la Activity → destruye el `PreviewView` y el `LifecycleOwner` asociados.
2. El ViewModel sobrevive → `CameraRepository` sigue apuntando a los objetos destruidos.
3. El Garbage Collector **no puede liberar** esos objetos porque hay una referencia viva.
4. Se crea una nueva Activity con un nuevo `PreviewView`, pero el anterior sigue en memoria.

---

## Causa raíz

`CameraRepository` necesita llamar a `rebindCameraXPreview()` tras un error/fin de grabación, para lo cual necesita el `LifecycleOwner` y el `PreviewView`. Se optó por guardarlos directamente en lugar de usar `WeakReference`.

---

## Impacto

- **Memory leak en cada rotación**: cada rotación retiene una copia antigua de la Activity en memoria.
- En dispositivos con poca RAM puede causar `OutOfMemoryError` tras varias rotaciones.
- En Android Studio con el Memory Profiler se verán múltiples instancias de `MainActivity` vivas simultáneamente.

---

## Cómo detectarlo

Usar Android Studio → Profiler → Memory:
1. Abrir la app.
2. Rotar el dispositivo 5 veces.
3. Forzar GC.
4. Hacer un heap dump.
5. Buscar instancias de `MainActivity` → deben haber 5+ instancias en lugar de 1.

O con LeakCanary:
```gradle
debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.12'
```
LeakCanary detectará el leak automáticamente al rotar.

---

## Solución paso a paso

### Paso 1 — Cambiar los campos a `WeakReference`

```kotlin
import java.lang.ref.WeakReference

// ANTES:
private var lastLifecycleOwner: LifecycleOwner? = null
private var lastPreviewView: PreviewView? = null

// DESPUÉS:
private var lastLifecycleOwnerRef: WeakReference<LifecycleOwner>? = null
private var lastPreviewViewRef: WeakReference<PreviewView>? = null
```

### Paso 2 — Actualizar `bindCamera()` para asignar las WeakReferences

```kotlin
fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
    lastLifecycleOwnerRef = WeakReference(lifecycleOwner)  // ← cambiar
    lastPreviewViewRef = WeakReference(previewView)         // ← cambiar
    // ... resto igual
}
```

### Paso 3 — Actualizar `rebindCameraXPreview()` para leer las WeakReferences

```kotlin
private fun rebindCameraXPreview() {
    val owner = lastLifecycleOwnerRef?.get()   // ← cambiar
    val previewView = lastPreviewViewRef?.get() // ← cambiar
    if (owner != null && previewView != null) {
        bindCamera(owner, previewView)
    }
    // Si las referencias son null, la Activity ya fue destruida → no hacer nada
}
```

### Paso 4 — Limpiar referencias en `stopRecording()` (opcional, buena práctica)

```kotlin
fun stopRecording() {
    // ...
    // No es necesario nullear las WeakReferences porque se invalidan solas,
    // pero podemos hacerlo para claridad:
    // lastLifecycleOwnerRef = null
    // lastPreviewViewRef = null
}
```

---

## Tests sugeridos

1. Instalar LeakCanary y rotar la pantalla 5 veces → no debe reportar ningún leak.
2. Iniciar grabación, rotar pantalla durante la grabación, parar → la grabación debe completarse y el preview debe rebindarse correctamente.
3. Heap dump tras 5 rotaciones → debe haber exactamente 1 instancia de `MainActivity`.
