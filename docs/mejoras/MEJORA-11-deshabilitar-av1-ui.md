# MEJORA-11 — Deshabilitar AV1 en el selector cuando no está disponible

| Campo | Valor |
|---|---|
| Prioridad | Alta |
| Tipo | Frontend |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

Marcar la opción AV1 en el picker de codecs como deshabilitada y no interactuable cuando el dispositivo no tiene encoder AV1 con soporte `COLOR_FormatSurface`.

> Ver BUG-06 para la implementación completa del `OptionRow` con opciones deshabilitadas.

---

## Implementación rápida

```kotlin
// En AppUiState:
val av1Supported: Boolean = false

// En el combine del ViewModel:
av1Supported = hasAv1SurfaceEncoder(),

// En CineCamScreen.kt, MenuPanel.Codec:
OptionRow(
    options = uiState.availableCodecs,
    selected = uiState.selectedCodec,
    disabledOptions = if (!uiState.av1Supported) setOf("AV1") else emptySet()
) { viewModel.setCodec(it) }
```

Ver [BUG-06](../bugs/BUG-06-av1-seleccionable-no-funcional.md) para el código completo del `OptionRow` con disabled state.

---

## Resultado visual

- Dispositivo **sin** AV1: opción aparece gris con texto "(no soportado)". No clickable.
- Dispositivo **con** AV1 (S23/S24 Android 14+): opción aparece normal y seleccionable.
