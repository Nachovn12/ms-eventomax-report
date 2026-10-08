# Contrato REST y Rangos Temporales (EMX-80)
**ESTADO: APROBADO CONDICIONADO por Ignacio para avanzar con EMX-81. Mapeo Kafka y DTOs definitivos NO congelados hasta aprobar el contrato mínimo de productions.events con Audit.**

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
Se proponen 5 indicadores calculados sobre el evento `ProductionStatusChanged` filtrando por `occurredAt` dentro del rango (APROBADOS como base inicial):
- Total de cambios de estado en el rango.
- Conteo de producciones por estado actual: Último estado observado de las producciones que tienen transiciones registradas. No representa el total real de producciones.
- Conteo de transiciones `previousStatus` -> `newStatus`.
- Producciones canceladas en el rango (`newStatus` = CANCELADO).
- Producciones cerradas en el rango (`newStatus` = CERRADO).

Los nombres de campo del JSON de respuesta quedan como "PROPUESTA, a aprobar".

**Notas:**
- Cobertura parcial: mientras solo existan cambios de estado, la respuesta de `/kpis` debe informar esta cobertura parcial y no presentarse como inventario completo.
- Los kpis usan `ProductionStatusChanged`, `eventId` para idempotencia y `occurredAt` para rangos.
- Criterio técnico para el KPI 2: el último estado de cada productionId se determina por mayor occurredAt, no por orden de llegada del mensaje.

### `/top-services`
PENDIENTE hasta contar con `serviceId` y datos reales de contratación. No bloquea el read model.

### Tiempo de montaje
Propuesta EN_MONTAJE -> EN_EJECUCION, pendiente de confirmación funcional. No se implementa.

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

*Nota: EMX-81 puede usar solo las columnas de la matriz de la sección 5. No agregar campos hasta congelar el contrato.*

## 6. Contrato Kafka v0.1 (provisional)
- **Topic:** `productions.events`
- **Serialización:** JSON
- **Consumer group propuesto:** `report-group`
- **Particiones locales:** 3
- **DLT de Report:** `productions.events.report.DLT` (PROPUESTA de Ignacio, con reintentos acotados y coordinación con infraestructura; se confirma en EMX-71)
- **Envelope:** `type`, `eventId`, `timestamp`, `traceId`, `correlationId`, `payload`
- **Estados válidos:** SOLICITADO, CONFIRMADO, EN_MONTAJE, EN_EJECUCION, CERRADO, CANCELADO

## 7. Registro de decisiones

### DECIDIDO
*(Fecha: 2026-10-08 | Fuente: Ignacio Valeria, revisión commit 302b76d)*
| Decisión | Detalle |
| :--- | :--- |
| KPIs aprobados | 5 KPIs propuestos aprobados como base inicial. |
| Corrección KPI 2 | Último estado observado de producciones con transiciones; no es total real. |
| Cobertura parcial | `/kpis` debe informar cobertura parcial si solo hay eventos de cambio de estado. |
| Funcionalidades diferidas | `top-services` y tiempo de montaje quedan diferidos sin bloquear el read model. |
| DLT propuesta | Nombre productions.events.report.DLT con reintentos acotados: PROPUESTA de Ignacio, se confirma en EMX-71. |
| Preferencia JWT | Report validará JWT vía OAuth2 Resource Server si BFF propaga Bearer; auth Admin en BFF; no expuesto públicamente. |

### PENDIENTE explícito
| Elemento pendiente | Referencia / Tarea |
| :--- | :--- |
| Confirmar nombre DLT | EMX-71 |
| Formalizar JWT | EMX-121 / EMX-72 |
| Incluir ProductionCreated en el contrato | EMX-71 |
| Aprobar y compartir contrato mínimo de productions.events con Audit | EMX-71 / Equipo |
| Definir servicio contratado y serviceId | Pendiente |
| Confirmación funcional del tiempo de montaje | Pendiente |
| Decisión sobre rango last30d | Pendiente |
| Congelar mapeo Kafka y DTOs definitivos (depende de aprobar el contrato mínimo con Audit) | EMX-71 / Equipo |
| Aprobar nombres de campo del JSON de respuesta de /kpis | EMX-80 |

## 8. Seguridad
- **Rol Funcional Requerido:** Admin.
- **Trust Boundary:** Preferencia de Ignacio: Report valida JWT con OAuth2 Resource Server si el BFF propaga el Bearer; la autorización Admin se mantiene en el BFF; el microservicio no se expone públicamente. Se formaliza en EMX-121/EMX-72. El BFF ya reenvía `Authorization` a Productions y Catalog, y la propagación hacia Report está pendiente de aprobación.
