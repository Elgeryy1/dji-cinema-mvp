# BUG-10 — Sin persistencia de ajustes de grabación

| Campo | Valor |
|---|---|
| Severidad | BAJO |
| Archivos | `CameraRepository.kt`, `MainViewModel.kt` |
| Esfuerzo estimado | 2 horas |

---

## Descripción

Los ajustes de grabación (resolución, fps, codec, rango dinámico, perfil de imagen, cameraId) se inicializan siempre con valores por defecto:

```kotlin
data class RecordingSettings(
    val resolution: String = "4K UHD",
    val fps: String = "30",
    val codec: String = "H.265/HEVC",
    val dynamicRange: String = "SDR",
    val pictureProfile: String = "Normal",
    val cameraId: String? = null
)
```

Cada vez que la app se cierra y vuelve a abrir, todos los ajustes se pierden. Un profesional que usa 1080p 120fps con H.265 debe reconfigurarlo en cada sesión.

---

## Causa raíz

No existe ningún mecanismo de persistencia (SharedPreferences, DataStore, Room) para los ajustes de grabación.

---

## Impacto

- Mala experiencia de usuario para cualquier usuario avanzado que use ajustes no predeterminados.
- Riesgo de grabar accidentalmente en configuración incorrecta (por ejemplo, usuario configuró 4K 60fps pero tras reiniciar está en 4K 30fps sin darse cuenta).

---

## Solución paso a paso

### Paso 1 — Añadir dependencia DataStore

En `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
```

### Paso 2 — Crear `RecordingSettingsStore`

Crear `app/src/main/java/com/cinemaapp/djimvp/camera/RecordingSettingsStore.kt`:

```kotlin
package com.cinemaapp.djimvp.camera

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recordingDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "recording_settings")

class RecordingSettingsStore(private val context: Context) {

    companion object {
        private val KEY_RESOLUTION    = stringPreferencesKey("resolution")
        private val KEY_FPS           = stringPreferencesKey("fps")
        private val KEY_CODEC         = stringPreferencesKey("codec")
        private val KEY_DYNAMIC_RANGE = stringPreferencesKey("dynamic_range")
        private val KEY_PICTURE_PROFILE = stringPreferencesKey("picture_profile")
        private val KEY_CAMERA_ID     = stringPreferencesKey("camera_id")
    }

    val settings: Flow<RecordingSettings> = context.recordingDataStore.data.map { prefs ->
        RecordingSettings(
            resolution     = prefs[KEY_RESOLUTION]     ?: "4K UHD",
            fps            = prefs[KEY_FPS]            ?: "30",
            codec          = prefs[KEY_CODEC]          ?: "H.265/HEVC",
            dynamicRange   = prefs[KEY_DYNAMIC_RANGE]  ?: "SDR",
            pictureProfile = prefs[KEY_PICTURE_PROFILE] ?: "Normal",
            cameraId       = prefs[KEY_CAMERA_ID]
        )
    }

    suspend fun save(settings: RecordingSettings) {
        context.recordingDataStore.edit { prefs ->
            prefs[KEY_RESOLUTION]      = settings.resolution
            prefs[KEY_FPS]             = settings.fps
            prefs[KEY_CODEC]           = settings.codec
            prefs[KEY_DYNAMIC_RANGE]   = settings.dynamicRange
            prefs[KEY_PICTURE_PROFILE] = settings.pictureProfile
            settings.cameraId?.let { prefs[KEY_CAMERA_ID] = it }
                ?: prefs.remove(KEY_CAMERA_ID)
        }
    }
}
```

### Paso 3 — Integrar en `CameraRepository`

```kotlin
class CameraRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = RecordingSettingsStore(appContext)
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Cargar ajustes guardados al inicializar
    init {
        repositoryScope.launch {
            settingsStore.settings.first().let { saved ->
                _state.update { it.copy(recordingSettings = saved) }
            }
        }
    }

    // Guardar cuando se cambian los ajustes
    fun setRecordingSettings(settings: RecordingSettings) {
        lastBindKey = null
        val validation = validateSettings(settings)
        _state.update {
            it.copy(
                recordingSettings = settings,
                appliedRecordingMode = validation.appliedMode,
                message = validation.blockingReason ?: "Reconfigurando ${settings.summary}"
            )
        }
        // Persistir de forma asíncrona
        repositoryScope.launch {
            settingsStore.save(settings)
        }
    }
}
```

### Paso 4 — Limpiar el scope al destruir el ViewModel

```kotlin
// En MainViewModel.kt:
override fun onCleared() {
    super.onCleared()
    djiRepository.release()
    cameraRepository.release()  // ← añadir este método
}

// En CameraRepository.kt:
fun release() {
    repositoryScope.cancel()
}
```

---

## Tests sugeridos

- Configurar 1080p 120fps H.265 → cerrar la app → reabrir → los ajustes deben persistir.
- Configurar resolución y cameraId específico → reiniciar → cameraId debe persistir.
- Primera instalación (DataStore vacío) → deben usarse los defaults correctos.
- Cambiar ajustes durante grabación activa → los ajustes se guardan pero la grabación actual no se interrumpe.
