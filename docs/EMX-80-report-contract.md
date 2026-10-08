# Contrato REST y Rangos Temporales (EMX-80)
**ESTADO: BORRADOR pendiente de aprobación**

## 1. Endpoints (solo lectura)
- `GET /api/report/kpis?range=last24h`
- `GET /api/report/top-services?range=last7d`

## 2. Rangos y semántica temporal (propuesta)
- **Rangos aceptados:** `last24h` y `last7d` para ambos endpoints.
- **Valores por defecto (si no se envía el parámetro `range`):**
  - `last24h` en el endpoint de KPIs.
  - `last7d` en el endpoint de top-services.
- **Ventana de tiempo:** `[ahora - duración, ahora]`. Se calculará utilizando la hora del servidor en UTC (`Instant`).
- **Timestamps:** Los timestamps de respuesta se enviarán en formato ISO-8601 UTC. El timezone de visualización debe ser resuelto por el frontend.
- **DECISIÓN PENDIENTE:** Evaluar si se acepta también el rango `last30d` u otros.

## 3. Manejo de Errores: 400 Bad Request (Rango inválido)
Se propone utilizar el formato estándar ProblemDetail (RFC 9457) de Spring Boot para los errores de validación del parámetro `range`. (Nota: Esta es una propuesta a validar).

**Ejemplo JSON:**
```json
{
  "type": "about:blank",
  "title": "Solicitud inválida",
  "status": 400,
  "detail": "El valor del parámetro 'range' no es válido.",
  "instance": "/api/report/kpis",
  "permittedValues": [
    "last24h",
    "last7d"
  ]
}
```

## 4. Estructura de Respuesta (DTOs)
La estructura de respuesta de `/kpis` y `/top-services` queda PENDIENTE de EMX-71. No se definen nombres de campo hasta conocer el contrato real de `productions.events`.

*(Nota: No se están inventando fórmulas de tiempo de montaje ni de serviceName; todo depende del evento EMX-71).*

## 5. Matriz de Mapeo
Mapeo de los campos del DTO al Read Model y a la estructura exacta de `productions.events`.

| Campo DTO | Campo read model | Campo exacto en productions.events |
| :--- | :--- | :--- |
| PENDIENTE EMX-71 | PENDIENTE EMX-71 | PENDIENTE EMX-71 |

## 6. Preguntas abiertas para Ignacio (EMX-71)
1) Lista exacta de campos del evento y un JSON de ejemplo.
2) ¿Existe eventId y cómo se garantiza que es único?
3) ¿Se publica serviceName o solo un id de servicio?
4) Tipos de evento y campos de fecha/timestamp disponibles.
5) Nombre del topic, consumer group, DLT y serialización.
6) ¿Qué campo permite calcular tiempo de montaje, si es que existe?
7) ¿El BFF reenviará el JWT a Report? (EMX-121)

## 7. Seguridad
- **Rol Funcional Requerido:** Admin.
- **Trust Boundary:** Pendiente de definición en EMX-121 / EMX-72.
