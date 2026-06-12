package com.cinemaapp.djimvp.dji

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import dji.common.Stick
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.gimbal.Attitude
import dji.common.gimbal.GimbalMode
import dji.common.gimbal.GimbalState
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.handheld.HardwareState
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.products.HandHeld
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DjiState(
    val sdkRegistered: Boolean = false,
    val sdkRegistrationState: String = "No registrado",
    val runtimePackageName: String = "",
    val manifestHasDjiKey: Boolean = false,
    val manifestDjiKeyLength: Int = 0,
    val manifestDjiKeyLast4: String = "",
    val djiHelperInstallAttempted: Boolean = false,
    val djiHelperInstallSucceeded: Boolean = false,
    val djiHelperInstallError: String? = null,
    val djiHelperInstallRawError: String? = null,
    val sdkRegisterError: String? = null,
    val sdkRegisterRawError: String? = null,
    val sdkRegisterAttempted: Boolean = false,
    val sdkRegisterInProgress: Boolean = false,
    val bluetoothState: String = "Inactivo",
    val isSearching: Boolean = false,
    val connected: Boolean = false,
    val connectedDeviceName: String = "",
    val devices: List<DjiBluetoothDevice> = emptyList(),
    val lastEvent: String = "Sin eventos",
    val joystickX: Int = 0,
    val joystickY: Int = 0,
    val zoomSliderState: String = "IDLE",
    val triggerState: String = "IDLE",
    val modeButtonState: String = "IDLE",
    val recordButtonState: String = "IDLE",
    val gimbalPitch: Float? = null,
    val gimbalYaw: Float? = null,
    val gimbalRoll: Float? = null,
    val gimbalMode: String = "FOLLOW",
    val errorMessage: String? = null
)

data class DjiBluetoothDevice(
    val id: String,
    val name: String,
    val description: String,
    internal val raw: Any
)

sealed interface DjiControlEvent {
    data object RecordClick : DjiControlEvent
    data object ZoomIn : DjiControlEvent
    data object ZoomOut : DjiControlEvent
    data object TriggerRecenter : DjiControlEvent
    data class Joystick(val x: Int, val y: Int) : DjiControlEvent
}

class DjiRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val initialDiagnostics = readDjiManifestDiagnostics(appContext)
    private val _state = MutableStateFlow(DjiState())
    val state: StateFlow<DjiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DjiControlEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<DjiControlEvent> = _events.asSharedFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRecordState = "IDLE"
    private var lastTriggerState = "IDLE"
    private var lastRecordEmitAt = 0L

    init {
        _state.update {
            it.copy(
                runtimePackageName = initialDiagnostics.packageName,
                manifestHasDjiKey = initialDiagnostics.hasKey,
                manifestDjiKeyLength = initialDiagnostics.keyLength,
                manifestDjiKeyLast4 = initialDiagnostics.keyLast4,
                djiHelperInstallAttempted = DjiSdkLoaderDiagnostics.helperInstallAttempted,
                djiHelperInstallSucceeded = DjiSdkLoaderDiagnostics.helperInstallSucceeded,
                djiHelperInstallError = DjiSdkLoaderDiagnostics.helperInstallError,
                djiHelperInstallRawError = DjiSdkLoaderDiagnostics.helperInstallRawError
            )
        }
    }

    fun registerSdk() {
        val current = _state.value
        if (current.sdkRegistered || current.sdkRegisterInProgress) {
            Log.i(
                "DJI_REGISTER",
                "registerSdk() ignored registered=${current.sdkRegistered} inProgress=${current.sdkRegisterInProgress}"
            )
            return
        }

        val diagnostics = readDjiManifestDiagnostics(appContext)
        Log.i(
            "DJI_REGISTER",
            "registerSdk() called helperAttempted=${DjiSdkLoaderDiagnostics.helperInstallAttempted} helperSucceeded=${DjiSdkLoaderDiagnostics.helperInstallSucceeded} helperError=${DjiSdkLoaderDiagnostics.helperInstallRawError}"
        )
        _state.update {
            it.copy(
                runtimePackageName = diagnostics.packageName,
                manifestHasDjiKey = diagnostics.hasKey,
                manifestDjiKeyLength = diagnostics.keyLength,
                manifestDjiKeyLast4 = diagnostics.keyLast4,
                djiHelperInstallAttempted = DjiSdkLoaderDiagnostics.helperInstallAttempted,
                djiHelperInstallSucceeded = DjiSdkLoaderDiagnostics.helperInstallSucceeded,
                djiHelperInstallError = DjiSdkLoaderDiagnostics.helperInstallError,
                djiHelperInstallRawError = DjiSdkLoaderDiagnostics.helperInstallRawError,
                sdkRegisterAttempted = true,
                sdkRegisterInProgress = true,
                sdkRegistrationState = "Registrando",
                sdkRegisterError = null,
                sdkRegisterRawError = null
            )
        }

        runCatching {
            val djiClassLoader = getDjiClassLoader()
            val managerClass = Class.forName("dji.sdk.sdkmanager.DJISDKManager", false, djiClassLoader)
            val callbackClass = Class.forName("dji.sdk.sdkmanager.DJISDKManager\$SDKManagerCallback", false, djiClassLoader)
            val sdkErrorClass = Class.forName("dji.common.error.DJISDKError", false, djiClassLoader)
            val registrationSuccess = sdkErrorClass.getField("REGISTRATION_SUCCESS").get(null)
            val manager = managerClass.getMethod("getInstance").invoke(null)
            managerClass.getMethod("setSupportOnlyForBluetoothDevice", Boolean::class.javaPrimitiveType).invoke(manager, true)
            val callback = Proxy.newProxyInstance(djiClassLoader, arrayOf(callbackClass)) { _, method, args ->
                when (method.name) {
                    "onRegister" -> {
                        val error = args?.getOrNull(0)
                        val registered = error == registrationSuccess
                        val description = readDjiErrorDescription(error)
                        val rawError = error?.toString()
                        val errorCode = readDjiErrorCode(error)
                        handleRegisterResult(
                            registered = registered,
                            description = description,
                            rawError = rawError,
                            errorCode = errorCode,
                            diagnostics = diagnostics
                        )
                        if (registered) {
                            managerClass.getMethod("startConnectionToProduct").invoke(manager)
                        }
                    }

                    "onProductDisconnect" -> {
                        _state.update {
                            it.copy(
                                connected = false,
                                connectedDeviceName = "",
                                bluetoothState = "Producto desconectado",
                                lastEvent = "DJI desconectado"
                            )
                        }
                    }

                    "onProductConnect", "onProductChanged" -> {
                        val product = args?.getOrNull(0)
                        Log.i("DJI_BT", "${method.name} product=${product} class=${product?.javaClass?.name}")
                        if (product is BaseProduct) {
                            bindProduct(product)
                        } else {
                            _state.update {
                                it.copy(
                                    connected = product != null,
                                    bluetoothState = if (product == null) "Sin producto DJI" else "Producto DJI conectado: ${product}",
                                    lastEvent = if (product == null) "Sin producto DJI" else "Producto DJI conectado"
                                )
                            }
                        }
                    }

                    "onComponentChange" -> {
                        _state.update { it.copy(lastEvent = "Componente DJI cambio: ${args?.getOrNull(0)}") }
                    }

                    "onInitProcess" -> {
                        _state.update { it.copy(sdkRegistrationState = "Inicializando ${args?.getOrNull(0)} ${args?.getOrNull(1)}%") }
                    }

                    "onDatabaseDownloadProgress" -> {
                        _state.update { it.copy(sdkRegistrationState = "FlySafe DB ${args?.getOrNull(0)}/${args?.getOrNull(1)}") }
                    }
                }
                null
            }
            managerClass.getMethod("registerApp", Context::class.java, callbackClass).invoke(manager, appContext, callback)
        }.onFailure { error ->
            Log.e("DJI_REGISTER", "registerSdk() exception=${error}", error)
            _state.update {
                it.copy(
                    sdkRegistered = false,
                    sdkRegisterInProgress = false,
                    sdkRegistrationState = "Fallo",
                    sdkRegisterError = error.message,
                    sdkRegisterRawError = error.toString(),
                    errorMessage = error.message
                )
            }
        }
    }

    private fun handleRegisterResult(
        registered: Boolean,
        description: String?,
        rawError: String?,
        errorCode: String?,
        diagnostics: DjiManifestDiagnostics
    ) {
                    Log.i(
                        "DJI_REGISTER",
                        "onRegister error=$rawError description=$description errorCode=$errorCode package=${diagnostics.packageName} hasKey=${diagnostics.hasKey} keyLength=${diagnostics.keyLength} keyLast4=${diagnostics.keyLast4}"
                    )
                    _state.update {
                        it.copy(
                            sdkRegistered = registered,
                            sdkRegisterInProgress = false,
                            sdkRegistrationState = if (registered) "Registrado" else "Fallo",
                            bluetoothState = if (registered) "Listo para Bluetooth" else it.bluetoothState,
                            sdkRegisterError = if (registered) null else description,
                            sdkRegisterRawError = if (registered) null else rawError,
                            errorMessage = if (registered) null else description
                        )
                    }
                    if (registered) {
                        Log.i("DJI_REGISTER", "SUCCESS")
                    }
    }

    fun searchBluetoothProducts() {
        runCatching {
            Log.i("DJI_BT", "searchBluetoothProducts() called")
            val djiClassLoader = getDjiClassLoader()
            ensureDjiBleListener(djiClassLoader)
            val connector = getBluetoothConnector(djiClassLoader)
            val devicesCallbackClass = Class.forName(
                "dji.sdk.sdkmanager.BluetoothProductConnector\$BluetoothDevicesListCallback",
                false,
                djiClassLoader
            )
            val completionCallbackClass = Class.forName(
                "dji.common.util.CommonCallbacks\$CompletionCallback",
                false,
                djiClassLoader
            )
            val devicesCallback = Proxy.newProxyInstance(djiClassLoader, arrayOf(devicesCallbackClass)) { _, method, args ->
                if (method.name == "onUpdate") {
                    val devices = (args?.getOrNull(0) as? List<*>).orEmpty()
                    Log.i("DJI_BT", "devices found=${devices.size}")
                    val mapped = devices.mapIndexed { index, device ->
                        val name = readMethod(device, "getName") ?: "DJI Bluetooth $index"
                        val rssi = readMethod(device, "getRssi") ?: "?"
                        val status = readMethod(device, "getStatus") ?: "?"
                    DjiBluetoothDevice(
                        id = "${name}_${rssi}_$index",
                        name = name,
                            description = "RSSI $rssi - $status",
                            raw = device ?: Any()
                    )
                }
                    _state.update {
                        it.copy(
                            devices = mapped,
                            bluetoothState = "Encontrados ${mapped.size}",
                            isSearching = false
                        )
                    }
                }
                null
            }
            _state.update { it.copy(isSearching = true, bluetoothState = "Buscando DJI Bluetooth") }
            connector.javaClass
                .getMethod("setBluetoothDevicesListCallback", devicesCallbackClass)
                .invoke(connector, devicesCallback)
            val completion = Proxy.newProxyInstance(djiClassLoader, arrayOf(completionCallbackClass)) { _, method, args ->
                if (method.name == "onResult") {
                    val error = args?.getOrNull(0)
                    val description = readDjiErrorDescription(error)
                    Log.i("DJI_BT", "searchBluetoothProducts onResult error=${error} description=$description")
                    _state.update {
                        it.copy(
                            isSearching = false,
                            bluetoothState = if (error == null) "Busqueda iniciada" else "Busqueda fallo: $description",
                            errorMessage = description
                        )
                    }
                }
                null
            }
            connector.javaClass
                .getMethod("searchBluetoothProducts", completionCallbackClass)
                .invoke(connector, completion)
        }.onFailure { error ->
            Log.e("DJI_BT", "searchBluetoothProducts exception=${error}", error)
            _state.update {
                it.copy(isSearching = false, bluetoothState = "Busqueda no disponible", errorMessage = error.message)
            }
        }
    }

    fun connectToDevice(id: String) {
        val device = _state.value.devices.firstOrNull { it.id == id } ?: return
        runCatching {
            val djiClassLoader = getDjiClassLoader()
            ensureDjiBleListener(djiClassLoader)
            val connector = getBluetoothConnector(djiClassLoader)
            val bluetoothDeviceClass = Class.forName("dji.sdk.sdkmanager.BluetoothDevice", false, djiClassLoader)
            val completionCallbackClass = Class.forName(
                "dji.common.util.CommonCallbacks\$CompletionCallback",
                false,
                djiClassLoader
            )
            Log.i("DJI_BT", "connectToDevice name=${device.name}")
            stopBluetoothSearchReflective(connector, completionCallbackClass)
            _state.update {
                it.copy(
                    bluetoothState = "Conectando a ${device.name}",
                    connectedDeviceName = device.name,
                    lastEvent = "Conectando DJI Bluetooth"
                )
            }
            val shouldStartProduct = AtomicBoolean(true)
            val completion = Proxy.newProxyInstance(djiClassLoader, arrayOf(completionCallbackClass)) { _, method, args ->
                if (method.name == "onResult") {
                    val error = args?.getOrNull(0)
                    val description = readDjiErrorDescription(error)
                    Log.i("DJI_BT", "connect onResult error=${error} description=$description")
                    val initialTimeout = description?.contains("timed out", ignoreCase = true) == true
                    if (error != null && !initialTimeout) {
                        shouldStartProduct.set(false)
                    }
                    _state.update {
                        it.copy(
                            bluetoothState = when {
                                error == null -> "Bluetooth conectado a ${device.name}"
                                initialTimeout -> "Bluetooth conectando a ${device.name}"
                                else -> "Conexion fallo: $description"
                            },
                            connectedDeviceName = device.name,
                            lastEvent = if (initialTimeout) "Timeout inicial BLE; esperando producto DJI" else it.lastEvent,
                            errorMessage = if (initialTimeout) null else description
                        )
                    }
                }
                null
            }
            mainHandler.postDelayed({
                runCatching {
                    connector.javaClass
                        .getMethod("connect", bluetoothDeviceClass, completionCallbackClass)
                        .invoke(connector, device.raw, completion)
                    Log.i("DJI_BT", "connect invoked for ${device.name}; waiting for result")
                    mainHandler.postDelayed({
                        if (shouldStartProduct.get()) {
                            runCatching {
                                _state.update {
                                    it.copy(
                                        bluetoothState = "Esperando producto DJI",
                                        lastEvent = "Arrancando conexion a producto"
                                    )
                                }
                                startConnectionToProductReflective(djiClassLoader)
                                pollProductAfterConnect()
                            }.onFailure { error ->
                                Log.e("DJI_BT", "startConnectionToProduct exception=${error}", error)
                                _state.update { it.copy(bluetoothState = "Producto DJI no disponible", errorMessage = error.message) }
                            }
                        } else {
                            Log.i("DJI_BT", "startConnectionToProduct skipped after connect error")
                        }
                    }, 2200L)
                }.onFailure { error ->
                    Log.e("DJI_BT", "connect invoke exception=${error}", error)
                    _state.update { it.copy(bluetoothState = "Conexion no disponible", errorMessage = error.message) }
                }
            }, 800L)
        }.onFailure { error ->
            Log.e("DJI_BT", "connectToDevice exception=${error}", error)
            _state.update { it.copy(bluetoothState = "Conexion no disponible", errorMessage = error.message) }
        }
    }

    fun disconnect() {
        runCatching {
            val djiClassLoader = getDjiClassLoader()
            val connector = getBluetoothConnector(djiClassLoader)
            val completionCallbackClass = Class.forName(
                "dji.common.util.CommonCallbacks\$CompletionCallback",
                false,
                djiClassLoader
            )
            Log.i("DJI_BT", "disconnect() called")
            val completion = Proxy.newProxyInstance(djiClassLoader, arrayOf(completionCallbackClass)) { _, method, args ->
                if (method.name == "onResult") {
                    val error = args?.getOrNull(0)
                    val description = readDjiErrorDescription(error)
                    Log.i("DJI_BT", "disconnect onResult error=${error} description=$description")
                _state.update {
                    it.copy(
                        connected = false,
                        connectedDeviceName = "",
                            bluetoothState = if (error == null) "Desconectado" else "Error al desconectar: $description",
                            errorMessage = description
                    )
                }
                }
                null
            }
            connector.javaClass
                .getMethod("disconnect", completionCallbackClass)
                .invoke(connector, completion)
        }.onFailure { error ->
            Log.e("DJI_BT", "disconnect exception=${error}", error)
            _state.update { it.copy(errorMessage = error.message) }
        }
    }

    /** Velocidad del gimbal por joystick (0.3..1.0). Persistible desde ajustes. */
    @Volatile
    var gimbalSpeedFactor: Float = 1f
        set(value) {
            field = value.coerceIn(0.2f, 1f)
        }

    /** Zona muerta del joystick en unidades crudas del stick (0..660). */
    @Volatile
    var joystickDeadzone: Int = 220

    private fun handHeldGimbal(): dji.sdk.gimbal.Gimbal? {
        return (DJISDKManager.getInstance().product as? HandHeld)?.gimbal
    }

    fun recenterGimbal() {
        val gimbal = handHeldGimbal()
        if (gimbal == null) {
            _state.update { it.copy(lastEvent = "Gimbal no disponible") }
            return
        }
        gimbal.reset { error ->
            _state.update {
                it.copy(
                    lastEvent = if (error == null) "Gimbal recentrado" else "Recentrar fallo: ${error.description}",
                    errorMessage = error?.description
                )
            }
        }
    }

    /** Modo del gimbal: "FOLLOW" (yaw follow), "FPV" o "FREE". */
    fun setGimbalMode(mode: String) {
        val gimbal = handHeldGimbal()
        if (gimbal == null) {
            _state.update { it.copy(lastEvent = "Gimbal no disponible") }
            return
        }
        val gimbalMode = when (mode.uppercase()) {
            "FPV" -> GimbalMode.FPV
            "FREE" -> GimbalMode.FREE
            else -> GimbalMode.YAW_FOLLOW
        }
        val label = when (gimbalMode) {
            GimbalMode.FPV -> "FPV"
            GimbalMode.FREE -> "FREE"
            else -> "FOLLOW"
        }
        gimbal.setMode(gimbalMode) { error ->
            _state.update {
                it.copy(
                    gimbalMode = if (error == null) label else it.gimbalMode,
                    lastEvent = if (error == null) "Gimbal modo $label" else "Modo $label fallo: ${error.description}",
                    errorMessage = error?.description
                )
            }
        }
    }

    /** Atajo de compatibilidad: modo follow. */
    fun setGimbalFollowMode() = setGimbalMode("FOLLOW")

    /** Gira el yaw 180° (modo selfie / mirar al operador). */
    fun rotateGimbalYaw180() {
        val gimbal = handHeldGimbal() ?: run {
            _state.update { it.copy(lastEvent = "Gimbal no disponible") }
            return
        }
        val rotation = Rotation.Builder()
            .mode(RotationMode.RELATIVE_ANGLE)
            .yaw(180f)
            .time(1.0)
            .build()
        gimbal.rotate(rotation) { error ->
            _state.update {
                it.copy(
                    lastEvent = if (error == null) "Gimbal girado 180°" else "Giro 180° fallo: ${error.description}",
                    errorMessage = error?.description
                )
            }
        }
    }

    /** Inclina el gimbal en pitch un delta relativo en grados (positivo = arriba). */
    fun tiltGimbal(degrees: Float) {
        val gimbal = handHeldGimbal() ?: return
        val rotation = Rotation.Builder()
            .mode(RotationMode.RELATIVE_ANGLE)
            .pitch(degrees)
            .time(0.4)
            .build()
        gimbal.rotate(rotation) { error ->
            if (error != null) {
                _state.update { it.copy(lastEvent = "Tilt gimbal fallo: ${error.description}", errorMessage = error.description) }
            }
        }
    }

    fun rotateGimbalFromJoystick(x: Int, y: Int) {
        val gimbal = handHeldGimbal() ?: return
        if (kotlin.math.abs(x) < joystickDeadzone && kotlin.math.abs(y) < joystickDeadzone) return
        val yawSpeed = (x / 660f * 20f * gimbalSpeedFactor).coerceIn(-20f, 20f)
        val pitchSpeed = (y / 660f * 15f * gimbalSpeedFactor).coerceIn(-15f, 15f)
        val rotation = Rotation.Builder()
            .mode(RotationMode.SPEED)
            .yaw(yawSpeed)
            .pitch(pitchSpeed)
            .time(0.2)
            .build()
        gimbal.rotate(rotation) { error ->
            if (error != null) {
                _state.update { it.copy(lastEvent = "Rotate gimbal fallo: ${error.description}", errorMessage = error.description) }
            }
        }
    }

    private fun bindProduct(product: BaseProduct?) {
        Log.i("DJI_BT", "bindProduct product=${product} model=${product?.model}")
        val handheld = product as? HandHeld
        if (handheld == null) {
            _state.update {
                it.copy(
                    connected = product != null,
                    bluetoothState = if (product == null) "Sin producto DJI" else "Producto no HandHeld: ${product.model}",
                    lastEvent = "Producto DJI no es HandHeld"
                )
            }
            return
        }
        _state.update {
            it.copy(
                connected = true,
                connectedDeviceName = handheld.model?.displayName ?: "DJI HandHeld",
                bluetoothState = "HandHeld conectado",
                lastEvent = "HandHeld conectado"
            )
        }
        handheld.handHeldController?.setHardwareStateCallback(object : HardwareState.Callback {
            override fun onUpdate(hardwareState: HardwareState) {
                Log.i(
                    "DJI_BT",
                    "hardwareState record=${hardwareState.recordAndShutterButtons} zoom=${hardwareState.zoomState} trigger=${hardwareState.triggerButton} stick=${hardwareState.stick}"
                )
                handleHardwareState(hardwareState)
            }
        })
        handheld.gimbal?.setStateCallback(object : GimbalState.Callback {
            override fun onUpdate(gimbalState: GimbalState) {
                val attitude: Attitude? = gimbalState.attitudeInDegrees
                _state.update {
                    it.copy(
                        gimbalPitch = attitude?.pitch,
                        gimbalYaw = attitude?.yaw,
                        gimbalRoll = attitude?.roll
                    )
                }
            }
        })
    }

    private fun pollProductAfterConnect(attempt: Int = 0) {
        mainHandler.postDelayed({
            val product = runCatching { DJISDKManager.getInstance().product }.getOrNull()
            Log.i("DJI_BT", "pollProductAfterConnect attempt=$attempt product=${product} model=${product?.model}")
            if (product != null) {
                bindProduct(product)
            } else if (attempt < 12) {
                pollProductAfterConnect(attempt + 1)
            } else {
                _state.update {
                    it.copy(
                        connected = false,
                        bluetoothState = "Producto DJI no detectado",
                        lastEvent = "Bluetooth enlazado, producto DJI sin callback"
                    )
                }
            }
        }, 1000L)
    }

    private fun handleHardwareState(hardwareState: HardwareState) {
        val record = readHardwareStateName(
            hardwareState,
            typedValue = hardwareState.recordAndShutterButtons,
            "getRecordAndShutterButtons",
            "getHandheldButtonStatus"
        )
        val trigger = readHardwareStateName(
            hardwareState,
            typedValue = hardwareState.triggerButton,
            "getTriggerButton",
            "getTriggerState"
        )
        val mode = readHardwareStateName(
            hardwareState,
            typedValue = hardwareState.modeButton,
            "getModeButton"
        )
        val zoom = readHardwareStateName(
            hardwareState,
            typedValue = hardwareState.zoomState,
            "getZoomState",
            "getZoomSlider"
        )
        val stick: Stick? = hardwareState.stick
        val x = stick?.horizontalPosition ?: 0
        val y = stick?.verticalPosition ?: 0

        _state.update {
            it.copy(
                lastEvent = "DJI $record / zoom $zoom / trigger $trigger",
                recordButtonState = record,
                triggerState = trigger,
                modeButtonState = mode,
                zoomSliderState = zoom,
                joystickX = x,
                joystickY = y
            )
        }

        val now = System.currentTimeMillis()
        val recordEvent = isRecordButtonEvent(record)
        val recordDebounced = now - lastRecordEmitAt > 650L
        if (recordEvent && lastRecordState != record && recordDebounced) {
            lastRecordEmitAt = now
            Log.i("DJI_BT", "record event emitted from $record")
            _events.tryEmit(DjiControlEvent.RecordClick)
        }
        if ((trigger == "SINGLE_CLICK" || trigger == "DOUBLE_CLICK") && lastTriggerState != trigger) {
            _events.tryEmit(DjiControlEvent.TriggerRecenter)
        }
        when (zoom) {
            "ZOOM_IN" -> _events.tryEmit(DjiControlEvent.ZoomIn)
            "ZOOM_OUT" -> _events.tryEmit(DjiControlEvent.ZoomOut)
        }
        if (x != 0 || y != 0) {
            _events.tryEmit(DjiControlEvent.Joystick(x, y))
        }
        lastRecordState = record
        lastTriggerState = trigger
    }

    private fun readHardwareStateName(
        hardwareState: HardwareState,
        typedValue: Any?,
        vararg getterNames: String
    ): String {
        val typedName = enumLikeName(typedValue)
        if (typedName != null && typedName != "UNKNOWN") return typedName

        getterNames.forEach { getter ->
            val value = runCatching {
                hardwareState.javaClass.methods
                    .firstOrNull { it.name == getter && it.parameterTypes.isEmpty() }
                    ?.invoke(hardwareState)
            }.getOrNull()
            val reflectedName = enumLikeName(value)
            if (reflectedName != null && reflectedName != "UNKNOWN") return reflectedName
        }
        return typedName ?: "UNKNOWN"
    }

    private fun enumLikeName(value: Any?): String? {
        if (value == null) return null
        return runCatching {
            val nameMethod = value.javaClass.methods
                .firstOrNull { it.name == "name" && it.parameterTypes.isEmpty() }
            (nameMethod?.invoke(value) ?: value).toString()
        }.getOrNull()
    }

    private fun isRecordButtonEvent(record: String): Boolean {
        val normalized = record.uppercase()
        if (normalized == "IDLE" || normalized == "UNKNOWN") return false
        return normalized.contains("RECORD") || normalized.contains("SHUTTER")
    }

    private fun getDjiClassLoader(): ClassLoader {
        return runCatching {
            val helperClass = Class.forName("com.cySdkyc.clx.Helper")
            (helperClass.getField("cl").get(null) as? ClassLoader) ?: appContext.classLoader
        }.getOrDefault(appContext.classLoader)
    }

    private fun getBluetoothConnector(djiClassLoader: ClassLoader): Any {
        val managerClass = Class.forName("dji.sdk.sdkmanager.DJISDKManager", false, djiClassLoader)
        val manager = managerClass.getMethod("getInstance").invoke(null)
        return requireNotNull(managerClass.getMethod("getBluetoothProductConnector").invoke(manager))
    }

    private fun ensureDjiBleListener(djiClassLoader: ClassLoader) {
        runCatching {
            val serviceClass = Class.forName("dji.midware.ble.BluetoothLeService", false, djiClassLoader)
            val bleClass = Class.forName("dji.midware.ble.BLE", false, djiClassLoader)
            val bleListenerClass = Class.forName("dji.midware.ble.BLE\$BLEListener", false, djiClassLoader)
            serviceClass.getMethod("setContext", Context::class.java).invoke(null, appContext)
            bleClass.getMethod("setContext", Context::class.java).invoke(null, appContext)
            val service = serviceClass.getMethod("getInstance").invoke(null)
            val ble = serviceClass.getMethod("getBLE").invoke(service)
            val initialized = bleClass.getMethod("init", bleListenerClass).invoke(ble, service)
            Log.i("DJI_BT", "BLE listener initialized=$initialized service=${service.javaClass.name}")
        }.onFailure { error ->
            Log.e("DJI_BT", "BLE listener init exception=${error}", error)
            _state.update {
                it.copy(
                    bluetoothState = "BLE init fallo",
                    errorMessage = error.message
                )
            }
        }
    }

    private fun stopBluetoothSearchReflective(connector: Any, completionCallbackClass: Class<*>) {
        runCatching {
            connector.javaClass
                .getMethod("stopSearchBluetoothProducts", completionCallbackClass)
                .invoke(connector, null)
            _state.update { it.copy(isSearching = false) }
            Log.i("DJI_BT", "stopSearchBluetoothProducts() before connect")
        }.onFailure { error ->
            Log.w("DJI_BT", "stopSearchBluetoothProducts exception=${error}")
        }
    }

    private fun startConnectionToProductReflective(djiClassLoader: ClassLoader) {
        val managerClass = Class.forName("dji.sdk.sdkmanager.DJISDKManager", false, djiClassLoader)
        val manager = managerClass.getMethod("getInstance").invoke(null)
        val started = managerClass.getMethod("startConnectionToProduct").invoke(manager)
        Log.i("DJI_BT", "startConnectionToProduct result=$started")
    }

    private fun readMethod(target: Any?, methodName: String): String? {
        if (target == null) return null
        return runCatching {
            target.javaClass.methods
                .firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?.invoke(target)
                ?.toString()
        }.getOrNull()
    }

    private fun readDjiErrorDescription(error: Any?): String? {
        if (error == null) return null
        return runCatching {
            error.javaClass.methods
                .firstOrNull { it.name == "getDescription" && it.parameterTypes.isEmpty() }
                ?.invoke(error)
                ?.toString()
        }.getOrNull()
    }

    private fun readDjiErrorCode(error: Any?): String? {
        if (error == null) return null
        return runCatching {
            error.javaClass.methods
                .firstOrNull { it.name == "getErrorCode" && it.parameterTypes.isEmpty() }
                ?.invoke(error)
                ?.toString()
        }.getOrNull()
    }
}
