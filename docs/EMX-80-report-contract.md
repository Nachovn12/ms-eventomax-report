# Contrato REST y Rangos Temporales (EMX-80)
**ESTADO: BORRADOR v0.1, pendiente de aprobación del equipo**

## 1. Endpoints (solo lectura)
- `GET /api/report/kpis?range=last24h`
- `GET /api/report/top-services?range=last7d`

## 2. Rangos y semántica temporal (propuesta)
- **Rangos aceptados:** `last24h` y `last7d` para ambos endpoints (top-services BLOQUEADO junto con el endpoint).
- **Valores por defecto (si no se envía el parámetro `range`):**
  - `last24h` en el endpoint de KPIs.
  - `last7d` en el endpoint de top-services (BLOQUEADO junto con el endpoint).
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
### `/kpis`
Se proponen 5 indicadores calculados sobre el evento `ProductionStatusChanged` filtrando por `occurredAt` dentro del rango:
- Total de cambios de estado en el rango.
- Conteo de producciones por estado actual (último `newStatus` por `productionId`). Limitación: el evento solo se emite al cambiar de estado, no al crear la producción; las producciones que siguen en SOLICITADO sin transiciones no aparecen. El estado actual se determina por mayor occurredAt, no por orden de llegada.
- Conteo de transiciones `previousStatus` -> `newStatus`.
- Producciones canceladas en el rango (`newStatus` = CANCELADO).
- Producciones cerradas en el rango (`newStatus` = CERRADO).

Los nombres de campo del JSON de respuesta quedan como "PROPUESTA, a aprobar".

### `/top-services`
BLOQUEADO. El evento v0.1 no incluye `serviceName` ni `serviceId`. Pendiente de definir qué es un servicio contratado.

### Tiempo de montaje
PENDIENTE. Fórmula propuesta `occurredAt(EN_EJECUCION) - occurredAt(EN_MONTAJE)` sin aprobar. No se implementa.

## 5. Matriz de Mapeo
| Campo DTO de respuesta | Campo read model | Campo exacto en productions.events |
| :--- | :--- | :--- |
| n/a (la respuesta son agregados; estructura PROPUESTA, a aprobar) | eventId | eventId (raíz del envelope, no dentro de payload) |
| n/a (la respuesta son agregados; estructura PROPUESTA, a aprobar) | type | type (raíz del envelope), se usa para procesar solo ProductionStatusChanged e ignorar otros tipos |
| n/a (la respuesta son agregados; estructura PROPUESTA, a aprobar) | productionId | payload.productionId |
| n/a (la respuesta son agregados; estructura PROPUESTA, a aprobar) | previousStatus | payload.previousStatus |
| n/a (la respuesta son agregados; estructura PROPUESTA, a aprobar) | newStatus | payload.newStatus |
| n/a (la respuesta son agregados; estructura PROPUESTA, a aprobar) | occurredAt | payload.occurredAt (Instant UTC; es el campo que filtra por rango) |

### Campos NO usados
| Campo | Razón de exclusión |
| :--- | :--- |
| organizerId | No requeridos por los KPIs propuestos |
| productionName | No requeridos por los KPIs propuestos |
| scheduledAt | No requeridos por los KPIs propuestos |
| location | No requeridos por los KPIs propuestos |
| timestamp | No requeridos por los KPIs propuestos |
| traceId | Solo para logs |
| correlationId | Solo para logs |

## 6. Contrato Kafka v0.1 (provisional)
- **Topic:** `productions.events`
- **Serialización:** JSON
- **Consumer group propuesto:** `report-group`
- **Particiones locales:** 3
- **DLT de Report:** PENDIENTE de definir
- **Envelope:** `type`, `eventId`, `timestamp`, `traceId`, `correlationId`, `payload`
- **Estados válidos:** SOLICITADO, CONFIRMADO, EN_MONTAJE, EN_EJECUCION, CERRADO, CANCELADO

## 7. Decisiones pendientes del equipo
1) Aprobar los 5 KPIs propuestos.
2) Qué representa un servicio contratado y cómo llega al evento (top-services).
3) Aprobar o no la fórmula de tiempo de montaje.
4) Nombre de la DLT de Report.
5) JWT: Report valida con OAuth2 Resource Server (propuesta de Ignacio) o confía solo en el BFF.
6) Si ProductionCreated entra en el alcance.
7) Cómo contar producciones sin eventos de cambio de estado (requeriría ProductionCreated).

## 8. Seguridad
- **Rol Funcional Requerido:** Admin.
- **Trust Boundary:** Pendiente de definición en EMX-121 / EMX-72. El BFF ya reenvía `Authorization` a Productions y Catalog, y la propagación hacia Report está pendiente de aprobación en EMX-72/121.
