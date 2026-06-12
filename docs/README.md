# DJICinemaMVP — Documentación técnica

App Android (Kotlin + Jetpack Compose) que convierte el smartphone Samsung en cámara de cine con control DJI.

---

## Bugs

| Archivo | Severidad | Esfuerzo |
|---|---|---|
| [BUG-01 — readActualVideoInfo duplicado](bugs/BUG-01-readActualVideoInfo-duplicado.md) | CRÍTICO | 30 min |
| [BUG-02 — Race condition timeout Camera2](bugs/BUG-02-race-condition-timeout-camera2.md) | CRÍTICO | 1h |
| [BUG-03 — Memory leak LifecycleOwner](bugs/BUG-03-memory-leak-lifecycleowner.md) | ALTO | 30 min |
| [BUG-04 — Reflexión DJI SDK frágil](bugs/BUG-04-reflexion-dji-sdk-fragil.md) | ALTO | 2-3 días |
| [BUG-05 — API deprecada orientación](bugs/BUG-05-api-deprecada-orientacion.md) | ALTO | 30 min |
| [BUG-06 — AV1 seleccionable no funcional](bugs/BUG-06-av1-seleccionable-no-funcional.md) | MEDIO | 1h (UX) |
| [BUG-07 — Métodos muertos CameraRepository](bugs/BUG-07-metodos-muertos-camerarepository.md) | MEDIO | 30 min |
| [BUG-08 — Polling DJI en background](bugs/BUG-08-polling-dji-background.md) | MEDIO | 2h |
| [BUG-09 — Strings hardcoded español](bugs/BUG-09-strings-hardcoded-espanol.md) | BAJO | 1h |
| [BUG-10 — Sin persistencia ajustes](bugs/BUG-10-sin-persistencia-ajustes.md) | BAJO | 2h |

---

## Mejoras

| Archivo | Tipo | Prioridad | Esfuerzo |
|---|---|---|---|
| [MEJORA-01 — Pipeline AV1 MediaCodec](mejoras/MEJORA-01-pipeline-av1-mediacodec.md) | Backend | Alta | 1-2 sem |
| [MEJORA-02 — Persistencia DataStore](mejoras/MEJORA-02-persistencia-datastore.md) | Backend | Alta | 2h |
| [MEJORA-03 — WeakReference leak fix](mejoras/MEJORA-03-weakreference-leak.md) | Backend | Alta | 30 min |
| [MEJORA-04 — Timeout cancelable](mejoras/MEJORA-04-timeout-cancelable.md) | Backend | Alta | 1h |
| [MEJORA-05 — Audio configurable](mejoras/MEJORA-05-audio-configurable.md) | Backend | Media | 3h |
| [MEJORA-06 — HDR10 y Dolby Vision](mejoras/MEJORA-06-hdr10-dolby-vision.md) | Backend | Media | 1-2 días |
| [MEJORA-07 — Timer de grabación](mejoras/MEJORA-07-timer-grabacion.md) | Backend+UI | Media | 1h |
| [MEJORA-08 — Eliminar código muerto](mejoras/MEJORA-08-eliminar-codigo-muerto.md) | Backend | Baja | 30 min |
| [MEJORA-09 — Slider de zoom](mejoras/MEJORA-09-slider-zoom.md) | Frontend | Alta | 2h |
| [MEJORA-10 — Timer HUD parpadeo](mejoras/MEJORA-10-timer-hud.md) | Frontend | Alta | 1h |
| [MEJORA-11 — Deshabilitar AV1 UI](mejoras/MEJORA-11-deshabilitar-av1-ui.md) | Frontend | Alta | 30 min |
| [MEJORA-12 — Horizon indicator gimbal](mejoras/MEJORA-12-horizon-indicator-gimbal.md) | Frontend | Media | 1 día |
| [MEJORA-13 — ErrorBanner dismissable](mejoras/MEJORA-13-error-banner-dismissable.md) | Frontend | Media | 30 min |
| [MEJORA-14 — Indicador almacenamiento](mejoras/MEJORA-14-indicador-almacenamiento.md) | Frontend | Media | 2h |
| [MEJORA-15 — Zoom en portrait](mejoras/MEJORA-15-zoom-portrait.md) | Frontend | Media | 1h |
| [MEJORA-16 — Miniatura último video](mejoras/MEJORA-16-miniatura-ultimo-video.md) | Frontend | Baja | 3h |
