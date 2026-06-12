# MEJORA-02 — Persistencia de ajustes con Jetpack DataStore

| Campo | Valor |
|---|---|
| Prioridad | Alta |
| Tipo | Backend |
| Esfuerzo estimado | 2 horas |

---

## Descripción

Guardar y recuperar automáticamente los ajustes de grabación (resolución, fps, codec, rango dinámico, perfil, cameraId) entre sesiones. El usuario configura una vez y la app recuerda su configuración.

> Ver también BUG-10 para el fix del bug relacionado.

---

## Implementación paso a paso

### Paso 1 — Añadir dependencia

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
```

### Paso 2 — Crear `RecordingSettingsStore`

Ver implementación completa en [BUG-10-sin-persistencia-ajustes.md](../bugs/BUG-10-sin-persistencia-ajustes.md).

### Paso 3 — Mostrar indicador de "ajustes guardados" en la UI

Añadir un breve toast o animación cuando se guardan los ajustes:

```kotlin
// En CineCamScreen.kt, en el MenuSheet:
LaunchedEffect(uiState.recordingProfileSummary) {
    // Mostrar "✓ Guardado" brevemente cuando cambia el perfil
    showSavedIndicator = true
    delay(2000)
    showSavedIndicator = false
}

if (showSavedIndicator) {
    Text(
        "✓ Ajustes guardados",
        color = Accent,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 4.dp)
    )
}
```

### Paso 4 — Añadir "Restablecer por defecto" en el panel Settings

```kotlin
// En SettingsMenu:
SmallButton("Restablecer ajustes") {
    viewModel.resetSettingsToDefaults()
}

// En MainViewModel:
fun resetSettingsToDefaults() {
    cameraRepository.setRecordingSettings(RecordingSettings())
}
```

---

## Tests sugeridos

- Cambiar a 1080p 120fps → cerrar app → reabrir → debe mostrar 1080p 120fps.
- Cambiar cameraId a "1" → reiniciar → cameraId "1" debe persistir.
- Restablecer por defecto → se deben usar los valores originales.
