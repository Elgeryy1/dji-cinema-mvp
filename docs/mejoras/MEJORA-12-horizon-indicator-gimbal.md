# MEJORA-12 — Horizon indicator del gimbal DJI en tiempo real

| Campo | Valor |
|---|---|
| Prioridad | Media |
| Tipo | Frontend |
| Esfuerzo estimado | 1 día |

---

## Descripción

Mostrar un indicador visual de pitch/roll/yaw del gimbal DJI como un artificial horizon (línea de horizonte) cuando el dispositivo DJI está conectado. Actualmente estos datos existen en `AppUiState` (`gimbalPitch`, `gimbalYaw`, `gimbalRoll`) pero solo se muestran como texto en el debug panel.

---

## Implementación

### Paso 1 — Crear el composable `GimbalHorizonIndicator`

```kotlin
@Composable
fun GimbalHorizonIndicator(
    pitch: Float,   // grados, positivo = inclinar arriba
    roll: Float,    // grados, positivo = rotar derecha
    modifier: Modifier = Modifier,
    size: Dp = 80.dp
) {
    val pitchClamped = pitch.coerceIn(-45f, 45f)
    val rollRad = Math.toRadians(roll.toDouble()).toFloat()

    Canvas(modifier = modifier.size(size)) {
        val cx = this.size.width / 2
        val cy = this.size.height / 2
        val radius = this.size.width / 2 - 4.dp.toPx()

        // Círculo exterior
        drawCircle(
            color = Color(0xFF2B343A),
            radius = radius,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Clip dentro del círculo
        clipPath(Path().apply { addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius)) }) {
            // Offset vertical por pitch: 1 grado = radius/45 * factor
            val pitchOffset = pitchClamped / 45f * radius * 0.8f

            // Línea de horizonte rotada por roll
            val lineLen = radius * 1.2f
            val cos = kotlin.math.cos(rollRad)
            val sin = kotlin.math.sin(rollRad)
            drawLine(
                color = Color(0xFF7DD3C7),
                start = Offset(cx - lineLen * cos, cy + pitchOffset + lineLen * sin),
                end = Offset(cx + lineLen * cos, cy + pitchOffset - lineLen * sin),
                strokeWidth = 2.dp.toPx()
            )

            // Fondo cielo/tierra
            val skyColor = Color(0xFF0D1B2A).copy(alpha = 0.7f)
            val groundColor = Color(0xFF2D1B00).copy(alpha = 0.7f)
            // Dibujar semi-rectángulos arriba y abajo de la línea de horizonte
        }

        // Crosshair central fijo
        val ch = 10.dp.toPx()
        drawLine(Color.White, Offset(cx - ch, cy), Offset(cx + ch, cy), 1.dp.toPx())
        drawLine(Color.White, Offset(cx, cy - ch), Offset(cx, cy + ch), 1.dp.toPx())

        // Indicador de roll en el arco superior
        val rollIndicatorAngle = (-90f + roll) * (Math.PI / 180f).toFloat()
        val indicatorR = radius - 6.dp.toPx()
        drawCircle(
            color = Color(0xFF7DD3C7),
            radius = 3.dp.toPx(),
            center = Offset(
                cx + indicatorR * kotlin.math.cos(rollIndicatorAngle),
                cy + indicatorR * kotlin.math.sin(rollIndicatorAngle)
            )
        )
    }
}
```

### Paso 2 — Integrar en el panel Gimbal

```kotlin
@Composable
private fun GimbalMenu(uiState: AppUiState, viewModel: MainViewModel) {
    Column {
        if (uiState.djiConnected) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GimbalHorizonIndicator(
                    pitch = uiState.gimbalPitch ?: 0f,
                    roll = uiState.gimbalRoll ?: 0f,
                    size = 80.dp
                )
                Column {
                    Text("P: ${uiState.gimbalPitch?.let { "%.1f°".format(it) } ?: "--"}", color = Accent, style = MaterialTheme.typography.labelSmall)
                    Text("Y: ${uiState.gimbalYaw?.let { "%.1f°".format(it) } ?: "--"}", color = Accent, style = MaterialTheme.typography.labelSmall)
                    Text("R: ${uiState.gimbalRoll?.let { "%.1f°".format(it) } ?: "--"}", color = Accent, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // ... resto del menú
    }
}
```

### Paso 3 — Mostrar en la HUD cuando DJI está conectado (opcional)

```kotlin
// En TopHud, esquina derecha cuando djiConnected:
if (uiState.djiConnected) {
    GimbalHorizonIndicator(
        pitch = uiState.gimbalPitch ?: 0f,
        roll = uiState.gimbalRoll ?: 0f,
        size = 36.dp,
        modifier = Modifier.padding(end = 8.dp)
    )
}
```

---

## Tests sugeridos

- Conectar OM2 y mover el gimbal → el horizon indicator debe moverse en tiempo real.
- Pitch positivo → la línea de horizonte debe bajar en el indicador.
- Roll positivo → la línea de horizonte debe rotar en sentido horario.
- Sin DJI conectado → el indicador no debe mostrarse.
