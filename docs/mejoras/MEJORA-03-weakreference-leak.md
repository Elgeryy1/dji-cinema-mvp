# MEJORA-03 — WeakReference para LifecycleOwner y PreviewView

| Campo | Valor |
|---|---|
| Prioridad | Alta |
| Tipo | Backend |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

Reemplazar las referencias fuertes a `LifecycleOwner` y `PreviewView` en `CameraRepository` por `WeakReference` para eliminar el memory leak en rotaciones de pantalla.

> Ver BUG-03 para la descripción completa del problema.

---

## Implementación

```kotlin
import java.lang.ref.WeakReference

// En CameraRepository.kt — cambiar campos:
private var lastLifecycleOwnerRef: WeakReference<LifecycleOwner>? = null
private var lastPreviewViewRef: WeakReference<PreviewView>? = null

// En bindCamera():
fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
    lastLifecycleOwnerRef = WeakReference(lifecycleOwner)
    lastPreviewViewRef = WeakReference(previewView)
    // ... resto igual
}

// En rebindCameraXPreview():
private fun rebindCameraXPreview() {
    val owner = lastLifecycleOwnerRef?.get() ?: return
    val previewView = lastPreviewViewRef?.get() ?: return
    bindCamera(owner, previewView)
}
```

---

## Tests sugeridos

- Instalar LeakCanary → rotar 5 veces → 0 leaks reportados.
- Heap dump: 1 sola instancia de `MainActivity` tras 5 rotaciones.
