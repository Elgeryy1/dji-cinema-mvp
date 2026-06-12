# MEJORA-08 — Eliminar código muerto en CameraRepository

| Campo | Valor |
|---|---|
| Prioridad | Baja |
| Tipo | Backend (limpieza) |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

Eliminar los métodos privados no utilizados de `CameraRepository.kt` que son restos de la implementación CameraX abandonada.

> Ver BUG-07 para la descripción completa con lista de métodos y líneas exactas.

---

## Checklist de eliminación

- [ ] `qualitySelectorFor(resolution: String)` — ~línea 310
- [ ] `frameRateFor(fps: String)` — ~línea 325
- [ ] `dynamicRangeFor(dynamicRange: String)` — ~línea 330
- [ ] `qualityFor(resolution: String)` — ~línea 429
- [ ] `readActualVideoInfo(uri: Uri)` (copia local) — ~línea 438
- [ ] Imports huérfanos de CameraX (`Quality`, `QualitySelector`, `VideoCapture`, `Recording`, etc.)

---

## Impacto esperado

- `CameraRepository.kt` reduce de ~477 líneas a ~390 líneas.
- Sin warnings de "unused" en Android Studio Analyze.
- Código más claro para futuros desarrolladores.

---

## Verificación

```bash
./gradlew assembleDebug
# 0 errores, 0 warnings de unused
```

Ver implementación detallada en [BUG-07-metodos-muertos-camerarepository.md](../bugs/BUG-07-metodos-muertos-camerarepository.md).
