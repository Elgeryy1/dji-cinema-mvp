# BUG-04 — Integración DJI SDK por reflexión frágil

| Campo | Valor |
|---|---|
| Severidad | ALTO |
| Archivo | `DjiRepository.kt` |
| Métodos | `getDjiClassLoader()`, `registerSdk()`, `searchBluetoothProducts()`, `connectToDevice()` |
| Esfuerzo estimado | 2-3 días (refactor completo) |

---

## Descripción

Toda la integración con el SDK DJI se realiza mediante **Java Reflection** (`Class.forName`, `Proxy.newProxyInstance`, `Method.invoke`). La razón es que el SDK DJI requiere un proceso helper (`DJI Virtual Stick Helper`) que se carga dinámicamente. Para acceder a sus clases se usa:

```kotlin
private fun getDjiClassLoader(): ClassLoader {
    return runCatching {
        val helperClass = Class.forName("com.cySdkyc.clx.Helper")
        (helperClass.getField("cl").get(null) as? ClassLoader) ?: appContext.classLoader
    }.getOrDefault(appContext.classLoader)
}
```

El nombre `com.cySdkyc.clx.Helper` es una clase **obfuscada** del SDK DJI. Este nombre cambia con cada versión del SDK. No hay ninguna garantía de estabilidad.

---

## Causa raíz

DJI Mobile SDK v5 para dispositivos handHeld requiere que ciertas operaciones pasen por un proceso helper APK separado. La comunidad de developers encontró que el ClassLoader del helper se puede obtener vía reflexión de esta clase obfuscada. Es un workaround no oficial.

---

## Impacto

- Cualquier actualización del DJI SDK o del DJI Virtual Stick Helper rompe la conexión DJI **en tiempo de ejecución** sin error de compilación.
- Los usuarios ven el error "Fallo registrando SDK DJI" sin información accionable.
- Imposible de testear estáticamente — solo falla en dispositivos reales con el hardware DJI conectado.

---

## Solución paso a paso

### Opción A — Migrar a DJI Mobile SDK v5 con AIDL (recomendada)

DJI Mobile SDK v5 expone una API pública que no requiere reflexión:

#### Paso 1 — Actualizar dependencias en `build.gradle.kts`

```kotlin
dependencies {
    implementation("com.dji:dji-sdk-v5-aircraft:5.9.0")      // o la última estable
    implementation("com.dji:dji-sdk-v5-networkImsdk:5.9.0")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:5.9.0")
}
```

#### Paso 2 — Reemplazar `registerSdk()` con la API pública

```kotlin
fun registerSdk() {
    val current = _state.value
    if (current.sdkRegistered || current.sdkRegisterInProgress) return

    _state.update { it.copy(sdkRegisterInProgress = true, sdkRegistrationState = "Registrando") }

    SDKManager.getInstance().init(appContext, object : SDKManagerCallback {
        override fun onRegisterSuccess() {
            _state.update {
                it.copy(
                    sdkRegistered = true,
                    sdkRegisterInProgress = false,
                    sdkRegistrationState = "Registrado",
                    bluetoothState = "Listo para Bluetooth"
                )
            }
        }

        override fun onRegisterFailure(error: IDJIError) {
            _state.update {
                it.copy(
                    sdkRegistered = false,
                    sdkRegisterInProgress = false,
                    sdkRegistrationState = "Fallo",
                    sdkRegisterError = error.description(),
                    errorMessage = error.description()
                )
            }
        }

        override fun onProductConnect(productId: Int) {
            bindProduct()
        }

        override fun onProductDisconnect(productId: Int) {
            _state.update { it.copy(connected = false, connectedDeviceName = "") }
        }

        override fun onProductChanged(productId: Int) { bindProduct() }
        override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
            _state.update { it.copy(sdkRegistrationState = "Inicializando ${totalProcess}%") }
        }
        override fun onDatabaseDownloadProgress(current: Long, total: Long) {
            _state.update { it.copy(sdkRegistrationState = "FlySafe DB $current/$total") }
        }
    })
}
```

#### Paso 3 — Reemplazar búsqueda Bluetooth

```kotlin
fun searchBluetoothProducts() {
    BluetoothProductConnector.getInstance()
        .searchBluetoothProducts(object : BluetoothProductConnector.DeviceListCallback {
            override fun onUpdate(devices: List<BluetoothDevice>) {
                val mapped = devices.map {
                    DjiBluetoothDevice(
                        id = it.name + "_" + it.rssi,
                        name = it.name,
                        description = "RSSI ${it.rssi}",
                        raw = it
                    )
                }
                _state.update { it.copy(devices = mapped, isSearching = false) }
            }
        }, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                _state.update { it.copy(bluetoothState = "Búsqueda iniciada") }
            }
            override fun onFailure(error: IDJIError) {
                _state.update { it.copy(isSearching = false, errorMessage = error.description()) }
            }
        })
}
```

### Opción B — Versión mínima: fijar la versión del SDK (parche temporal)

Si no hay tiempo para el refactor completo, al menos fijar la versión del SDK DJI en `build.gradle.kts` para prevenir actualizaciones automáticas que rompan la reflexión:

```kotlin
configurations.all {
    resolutionStrategy {
        force("com.dji:dji-sdk:4.16.4")  // fijar a la versión que funciona
    }
}
```

Y documentar explícitamente en el código cuál versión del helper APK es compatible:

```kotlin
// IMPORTANTE: getDjiClassLoader() usa la clase obfuscada "com.cySdkyc.clx.Helper"
// Solo funciona con DJI Virtual Stick Helper APK versión X.Y.Z
// NO actualizar el SDK DJI sin verificar que el nombre de la clase no ha cambiado
```

---

## Tests sugeridos

- Conectar gimbal DJI OM2 → `sdkRegistered` debe ser `true`.
- Pulsar botón Record en el controlador → `CameraRepository.toggleRecording()` debe llamarse.
- Mover el joystick → el gimbal debe rotar.
- Actualizar el SDK a una versión mayor → verificar que la integración sigue funcionando.
