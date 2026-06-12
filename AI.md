# DJICinemaMVP — Contexto para IA

## Qué hace
App Android que convierte un Samsung Galaxy en cámara de cine profesional controlada por un gimbal DJI OM2/OM5 vía Bluetooth. Preview via CameraX, grabación via Camera2 API (high-speed hasta 120fps+), control físico DJI (record, zoom, gimbal).

## Stack
Kotlin · Jetpack Compose · Camera2 API · CameraX (solo preview) · DJI Mobile SDK v5 (via reflexión) · MediaRecorder · MediaStore · BLE · minSdk 26 · targetSdk 35

## Mapa de archivos clave
```
app/src/main/java/com/cinemaapp/djimvp/
├── MainActivity.kt              — ComponentActivity, setContent, nada más
├── MainViewModel.kt             — ViewModel central. Combina 3 flows (camera, advanced, dji) → AppUiState
├── state/AppUiState.kt          — Data class gigante con todo el estado UI (60+ campos)
├── camera/
│   ├── CameraRepository.kt      — Gestiona CameraX (preview) + Camera2 (grabación). StateFlow<CameraState>
│   ├── Camera2HighSpeedRecorder.kt — Pipeline Camera2 completa: openCamera→session→MediaRecorder→MediaStore
│   └── SamsungCameraCapabilitiesRepository.kt — Escanea cámaras del dispositivo para detectar modos soportados
├── dji/
│   ├── DjiRepository.kt         — TODO el SDK DJI cargado via Java Reflection. StateFlow<DjiState> + SharedFlow<DjiControlEvent>
│   ├── DjiManifestDiagnostics.kt — Lee la API key DJI del manifest para diagnóstico
│   └── DjiSdkLoaderDiagnostics.kt — Diagnóstico del helper APK de DJI
└── ui/
    ├── CineCamScreen.kt         — UI completa en Compose. Portrait + Landscape. MenuPanel enum para subpaneles
    ├── CameraPreview.kt         — AndroidView que aloja el PreviewView de CameraX
    └── theme/                   — Colores oscuros estilo cine, dynamicColor=false
```

## Arquitectura en pocas líneas
- **Un solo ViewModel** (`MainViewModel`) que agrega con `combine()` tres repositorios independientes.
- **CameraRepository** es el cerebro de la cámara: tiene `lastLifecycleOwner`/`lastPreviewView` para rebindear el preview tras grabar (BUG: referencias fuertes → memory leak).
- **Toda la grabación va por Camera2** (`Camera2HighSpeedRecorder`), no por CameraX `VideoCapture`. CameraX solo hace preview.
- **DJI vía reflexión**: `getDjiClassLoader()` busca `com.cySdkyc.clx.Helper` (clase obfuscada). Si falla, devuelve el classloader del app. Todo el SDK se invoca con `Class.forName` + `Proxy.newProxyInstance`.
- **Flujo de eventos DJI**: `DjiRepository` emite `DjiControlEvent` en un `SharedFlow`. El ViewModel los recoge y los mapea a acciones de cámara (record, zoom, gimbal rotate).
- **Calibración de zoom**: animación suave via `Handler.postDelayed` en pasos de 0.035f cada 16ms.

## Convenciones del código
- Español en mensajes de estado/error UI. Inglés en nombres de clases/variables.
- `_state = MutableStateFlow(...)` + `val state = _state.asStateFlow()` en todos los repositorios.
- `runCatching { }.onFailure { }` para todo el código Camera2 y DJI.
- `mainExecutor.execute { }` para callbacks de Camera2 que necesitan actualizar UI.
- Logs con tags: `"CINECAM_CAMERA"`, `"CINECAM_PERMS"`, `"DJI_REGISTER"`, `"DJI_BT"`.

## Bugs críticos conocidos (ver docs/bugs/)
1. **BUG-02**: `postDelayed` watchdog de 5s nunca se cancela → race condition si la grabación para antes.
2. **BUG-03**: `lastLifecycleOwner` y `lastPreviewView` son referencias fuertes en el ViewModel → memory leak en rotaciones.
3. **BUG-04**: Reflexión DJI depende de `"com.cySdkyc.clx.Helper"` — rompe con cada update del SDK DJI.
4. **BUG-10**: Sin persistencia de ajustes (DataStore comentado en build.gradle).

## Gotchas críticos
- **AV1 está BLOQUEADO**: `startRecording()` retorna si `settings.codec == "AV1"` con un mensaje. No grabar AV1 via MediaRecorder — no funciona en Samsung. Pendiente implementar vía `MediaCodec+MediaMuxer`.
- **El preview es CameraX, la grabación es Camera2**: al iniciar grabación, `cameraProvider.unbindAll()` se llama para liberar la cámara, y Camera2 la toma. Al finalizar, `rebindCameraXPreview()` devuelve el preview a CameraX.
- **ConstrainedHighSpeedCaptureSession** para fps > 60: requiere `createConstrainedHighSpeedCaptureSession()` + `createHighSpeedRequestList()` + `setRepeatingBurst()`. No usar `setRepeatingRequest()` para high-speed.
- **MediaStore IS_PENDING**: no se usa (solo Camera2HighSpeedRecorder no lo usa, crea el Uri con insert() sin IS_PENDING=1). Si se añade IS_PENDING, actualizar en el stop().
- **`DjiControlEvent.ZoomIn/Out`** se emiten continuamente mientras el slider DJI se mueve — no hay debouncing. Si se añade debouncing, hacerlo en el ViewModel, no en DjiRepository.
- **`SamsungCameraCapabilitiesRepository`** puede tardar varios segundos en escanear — lo hace en background con `viewModelScope.launch`. No bloquear UI esperándolo.
- **`bestFpsForResolution()`** en MainViewModel usa `_uiState.value.supportedVideoModes` que puede estar vacío al inicio. El fallback `ifEmpty { listOf("24","25","30","50","60") }` cubre esto.

## Estado actual del proyecto
- ✅ Preview CameraX funciona
- ✅ Grabación Camera2 H.264/H.265 funciona
- ✅ Zoom hardware funciona (smooth animation)
- ✅ Conexión DJI BLE funciona (via reflexión)
- ✅ Control físico (record, zoom, gimbal) funciona
- ❌ AV1 no implementado (pendiente MediaCodec pipeline)
- ❌ HDR10/Dolby Vision no implementado (solo HLG10)
- ❌ Sin persistencia de ajustes
- ❌ Memory leak en rotaciones (WeakReference pendiente)
- ⚠️ La reflexión DJI puede romperse en cualquier update del SDK

## Cómo añadir una nueva feature
1. Si es estado UI → añadir campo en `AppUiState.kt` + proyectarlo en el `combine()` de `MainViewModel`.
2. Si es acción del usuario → `fun setXxx()` en `MainViewModel` → delegarlo al repositorio correcto.
3. Si es nueva UI → añadir un `MenuPanel` nuevo o componente Compose en `CineCamScreen.kt`.
4. Si es nueva captura de datos de cámara → `SamsungCameraCapabilitiesRepository` o `CameraRepository`.
5. Si es nueva interacción DJI → añadir al sealed `DjiControlEvent` + manejarlo en el `collect` del ViewModel.
