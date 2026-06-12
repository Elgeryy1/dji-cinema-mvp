# MEJORA-10 — Timer de grabación en la HUD con parpadeo

| Campo | Valor |
|---|---|
| Prioridad | Alta |
| Tipo | Frontend |
| Esfuerzo estimado | 1 hora (requiere MEJORA-07 backend) |

---

## Descripción

Mostrar el contador de tiempo de grabación en la HUD superior junto al indicador REC, con un punto rojo parpadeante animado.

> Requiere el `StateFlow<Long> recordingElapsedMs` de [MEJORA-07](MEJORA-07-timer-grabacion.md).

---

## Implementación

Ver código completo en MEJORA-07. La implementación frontend incluye:

1. El composable `RecordingDot()` con animación de opacidad parpadeante.
2. El `TopHud` actualizado para mostrar el dot + timer durante grabación.
3. La función helper `formatElapsed(ms: Long): String` para HH:MM:SS.

---

## Resultado visual esperado

```
[LogiQD CineCam] [● 02:34]              [4K 30 HEVC SDR]
```

donde ● parpadea entre opacidad 1.0 y 0.2 cada 600ms.
