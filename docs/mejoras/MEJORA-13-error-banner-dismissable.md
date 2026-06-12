# MEJORA-13 — ErrorBanner dismissable con botón ×

| Campo | Valor |
|---|---|
| Prioridad | Media |
| Tipo | Frontend |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

El `ErrorBanner` actual muestra un máximo de 2 líneas de error pero no tiene botón para cerrarlo. El usuario debe esperar a que otro estado lo limpie. Añadir un botón "×" que llame `viewModel.clearError()`.

---

## Implementación

### Paso 1 — Actualizar `ErrorBanner` para aceptar `onDismiss`

```kotlin
@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xDD2A1111))
            .border(BorderStroke(1.dp, Color(0xFFB94B4B)), RoundedCornerShape(8.dp))
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message,
            color = Color(0xFFFFD7D7),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        // Botón ×
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { onDismiss() }
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("×", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
        }
    }
}
```

### Paso 2 — Actualizar la llamada en `CameraStage`

```kotlin
uiState.errorMessage?.let {
    ErrorBanner(
        message = it,
        onDismiss = viewModel::clearError,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 12.dp, top = 70.dp, end = 12.dp)
    )
}
```

---

## Tests sugeridos

- Provocar un error → banner aparece con botón ×.
- Pulsar × → banner desaparece, `uiState.errorMessage` es null.
- Nuevo error tras dismiss → el banner vuelve a aparecer.
