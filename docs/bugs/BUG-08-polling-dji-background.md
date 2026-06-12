# BUG-08 — Polling DJI activo en background tras conectar Bluetooth

| Campo | Valor |
|---|---|
| Severidad | MEDIO |
| Archivo | `DjiRepository.kt` |
| Método | `pollProductAfterConnect()` |
| Esfuerzo estimado | 2 horas |

---

## Descripción

Cuando se conecta un dispositivo DJI por Bluetooth, el código lanza un polling de 12 iteraciones con 1 segundo de delay:

```kotlin
private fun pollProductAfterConnect(attempt: Int = 0) {
    mainHandler.postDelayed({
        val product = runCatching { DJISDKManager.getInstance().product }.getOrNull()
        if (product != null) {
            bindProduct(product)
        } else if (attempt < 12) {
            pollProductAfterConnect(attempt + 1)
        } else {
            _state.update { ... }
        }
    }, 1000L)
}
```

**El problema:** este polling se ejecuta en el Main Thread y **no se cancela** si:
- La app va al background
- El usuario rota la pantalla
- El usuario desconecta el dispositivo manualmente
- El ViewModel es destruido

Si el polling completa en el background o tras una rotación, actualiza `_state` con un producto DJI que puede haber cambiado o ser inválido para la nueva instancia de UI.

---

## Causa raíz

El polling fue implementado como workaround porque el callback `onProductConnect` del SDK DJI (vía reflexión) no siempre dispara inmediatamente. El polling "fuerza" la detección del producto.

---

## Impacto

- Estado inconsistente entre UI y backend: `djiConnected = true` cuando el gimbal ya fue desconectado.
- El main thread queda ocupado con callbacks durante 12 segundos.
- Si el usuario rota el dispositivo durante la conexión, el polling puede completar en la nueva Activity con referencias a la antigua.

---

## Solución paso a paso

### Paso 1 — Mover el polling a una coroutine cancelable

`DjiRepository` no tiene un `CoroutineScope` propio. Añadirlo:

```kotlin
class DjiRepository(private val context: Context) {
    // ...
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )
    private var pollJob: kotlinx.coroutines.Job? = null
    // ...
}
```

### Paso 2 — Reemplazar `pollProductAfterConnect()` con una coroutine

```kotlin
private fun pollProductAfterConnect() {
    pollJob?.cancel()
    pollJob = repositoryScope.launch {
        repeat(12) { attempt ->
            delay(1000L)
            val product = runCatching { DJISDKManager.getInstance().product }.getOrNull()
            Log.i("DJI_BT", "poll attempt=$attempt product=${product?.model}")
            if (product != null) {
                bindProduct(product)
                return@launch  // éxito — cancelar el polling
            }
        }
        // 12 intentos fallidos
        _state.update {
            it.copy(
                connected = false,
                bluetoothState = "Producto DJI no detectado tras 12s",
                lastEvent = "Bluetooth enlazado, producto DJI sin callback"
            )
        }
    }
}
```

### Paso 3 — Cancelar el polling cuando se desconecta

```kotlin
fun disconnect() {
    pollJob?.cancel()
    pollJob = null
    // ... resto del código de desconexión
}
```

### Paso 4 — Limpiar en `onCleared()` del ViewModel

El `DjiRepository` no tiene `onCleared()` propio. Añadir un método `release()` que el ViewModel llame:

```kotlin
// En DjiRepository:
fun release() {
    pollJob?.cancel()
    repositoryScope.cancel()
}

// En MainViewModel:
override fun onCleared() {
    super.onCleared()
    djiRepository.release()
}
```

---

## Tests sugeridos

- Conectar gimbal, rotar dispositivo durante los 12s de polling → no debe haber estado inconsistente tras la rotación.
- Conectar gimbal, presionar atrás (destruir ViewModel) durante polling → no debe haber crash ni estado residual.
- Conectar gimbal exitosamente → `pollJob` debe cancelarse antes del intento 12.
- Desconectar manualmente → `pollJob` debe cancelarse inmediatamente.
