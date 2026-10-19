# Contrato REST y Rangos Temporales (EMX-80)

**ESTADO: ACTUALIZADO Y ALINEADO CON `productions.events` v1.0.**
> **Alcance:** Define el contrato formal para el microservicio `ms-eventomax-report`, su read model y la integración contractual con los eventos de dominio de producciones. Distingue expresamente la compatibilidad contractual de la integración E2E en ambiente distribuido.

---

## 1. Endpoints de Lectura (REST)

- `GET /api/report/kpis?range=last24h`
- `GET /api/report/top-services?range=last7d`

### 1.1. Seguridad y Trust Boundary (Alineación EMX-121 y BFF)
- **Defensa en Profundidad:** El BFF (`ms-eventomax-bff`) protege las rutas hacia Report y propaga el encabezado `Authorization: Bearer <token>`. Report valida de forma autónoma la firma, emisor (`ENTRA_ISSUER_URI`), audiencia (`ENTRA_AUDIENCE`) y vigencia del JWT como OAuth2 Resource Server.
- **Roles y Scopes Exigidos:** Requiere simultáneamente la autoridad `ROLE_Admin` (claim `roles` con `"Admin"`) y `SCOPE_access_as_user` (claim `scp` con `"access_as_user"`).
- **Mapeo de Errores:** Responde con RFC 9457 `ProblemDetail` en español: `401 Unauthorized` si no hay token o es inválido/audiencia incorrecta, y `403 Forbidden` si el rol no es Admin, falta el scope o se invoca un método de escritura.
- **Exposición y Red Interna:** `ms-eventomax-report` reside en la red interna privada de microservicios sin ruta pública en el API Gateway ni puertos públicos expuestos.

---

## 2. Rangos y Semántica Temporal

- **Rangos aceptados:** `last24h` y `last7d` para ambos endpoints.
- **Valores por defecto (si no se envía el parámetro `range`):**
  - `last24h` en el endpoint de KPIs.
  - `last7d` en el endpoint de top-services (actualmente responde `501 Not Implemented`).
- **Ventana de tiempo:** `[now - duración, now]`. Se calcula utilizando la hora del servidor en UTC (`Instant`).
- **Timestamps:** Los timestamps de respuesta se serializan en formato ISO-8601 UTC (`Instant`). La conversión a zona horaria local es responsabilidad del frontend.

---

## 3. Manejo de Errores: 400 Bad Request (Rango inválido)

Utiliza el estándar RFC 9457 `ProblemDetail` de Spring Framework para errores de validación del parámetro `range`.

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

---

## 4. Estructura de Respuesta (DTOs)

### 4.1. `GET /api/report/kpis` (200 OK)
Provee 5 indicadores calculados sobre los hechos persistidos de `ProductionStatusChanged` filtrando por `occurredAt` dentro de la ventana:
1. `totalStatusChanges`: Total de transiciones de estado registradas en el rango.
2. `currentStatusDistribution`: Distribución de estados observados (último estado de cada producción con transiciones en el rango, resuelto por mayor `occurredAt` y desempate determinista por `eventId DESC`). No es un inventario absoluto del sistema.
3. `transitions`: Lista de transiciones observadas (`previousStatus` -> `newStatus`) con su respectivo conteo.
4. `cancelledCount`: Cantidad de producciones canceladas (`newStatus = CANCELADO`) en el rango.
5. `closedCount`: Cantidad de producciones cerradas (`newStatus = CERRADO`) en el rango.

#### Advertencia Obligatoria de Cobertura Parcial:
Dado que el microservicio únicamente procesa eventos de cambio de estado, la respuesta incluye explícitamente el objeto `partialCoverage`:
```json
"partialCoverage": {
  "isPartial": true,
  "description": "Métricas calculadas exclusivamente sobre eventos de transición de estado (ProductionStatusChanged). No representa el inventario total de producciones del sistema ni incluye producciones sin cambios de estado en el rango."
}
```

### 4.2. `GET /api/report/top-services` (501 Not Implemented)
Retorna HTTP 501 `ProblemDetail`. Este endpoint permanece diferido debido a que el contrato v1.0 de `productions.events` y el modelo de dominio no publican `serviceId` ni contrataciones de servicios (Productions solo reserva equipamiento en Catalog).

---

## 5. Read Model y Persistencia PostgreSQL

El esquema de base de datos se versiona estrictamente mediante Flyway:
- **Archivo de migración real:** `src/main/resources/db/migration/V1__create_report_read_model.sql`
- **Validación JPA en producción/desarrollo:** `spring.jpa.hibernate.ddl-auto=validate`

### 5.1. Tablas y Claves Reales

1. **`processed_event` (Control de Idempotencia):**
   - `event_id UUID PRIMARY KEY`: Identificador único del evento recibido en el envelope.
   - `processed_at TIMESTAMP WITH TIME ZONE NOT NULL`: Marca temporal UTC de inserción.
   - *Garantía atómica:* Inserción nativa `INSERT INTO processed_event (event_id, processed_at) VALUES (:eventId, :processedAt) ON CONFLICT DO NOTHING`. Si retorna `0`, el evento es ignorado por duplicidad.

2. **`report_production_fact` (Hechos de Estado):**
   - `id UUID PRIMARY KEY`: Identificador único del hecho (clave primaria UUID, **no BIGINT**).
   - `event_id UUID NOT NULL UNIQUE`: Clave foránea al evento procesado (`fk_report_fact_event REFERENCES processed_event(event_id)`).
   - `type VARCHAR(50) NOT NULL`: Tipo de evento procesado (`ProductionStatusChanged`).
   - `production_id BIGINT NOT NULL`: Identificador de la producción.
   - `previous_status VARCHAR(50) NOT NULL`: Estado de origen (`chk_previous_status` enum).
   - `new_status VARCHAR(50) NOT NULL`: Estado de destino (`chk_new_status` enum).
   - `occurred_at TIMESTAMP WITH TIME ZONE NOT NULL`: Momento en que ocurrió el cambio (filtro temporal).

### 5.2. Restricciones y Check Constraints
- `CONSTRAINT fk_report_fact_event FOREIGN KEY (event_id) REFERENCES processed_event(event_id)`
- `CONSTRAINT chk_previous_status CHECK (previous_status IN ('SOLICITADO', 'CONFIRMADO', 'EN_MONTAJE', 'EN_EJECUCION', 'CERRADO', 'CANCELADO'))`
- `CONSTRAINT chk_new_status CHECK (new_status IN ('SOLICITADO', 'CONFIRMADO', 'EN_MONTAJE', 'EN_EJECUCION', 'CERRADO', 'CANCELADO'))`

### 5.3. Índices Existentes
- `idx_fact_occurred_at ON report_production_fact(occurred_at)`
- `idx_fact_prod_occurred ON report_production_fact(production_id, occurred_at DESC)`
- `idx_fact_status_occurred ON report_production_fact(new_status, occurred_at)`

---

## 6. Contrato Kafka `productions.events` v1.0

### 6.1. Definición del Topic y Grupos
- **Topic:** `productions.events`
- **Consumer Group:** `report-group` (configurable vía `KAFKA_CONSUMER_GROUP`)
- **DLT:** `productions.events.report.DLT` (configurable vía `KAFKA_TOPIC_REPORT_DLT`)
- **Particiones mínimas:** 3

### 6.2. Envelope v1.0 Oficial (`KafkaEventEnvelope<T>`)
Emitido por `ms-eventomax-productions`:
| Campo | Tipo | Requerido | Descripción |
| :--- | :--- | :--- | :--- |
| `schemaVersion` | String | Sí | Versión del esquema (`"1.0"`). |
| `eventId` | UUID | Sí | Identificador único del evento (raíz del envelope). |
| `type` | String | Sí | Tipo de evento (`ProductionCreated`, `ProductionStatusChanged`). |
| `timestamp` | Instant (ISO-8601) | Sí | Timestamp de emisión del evento en el producer. |
| `traceId` | String | No | Trace ID distribuido W3C / B3 para observabilidad. |
| `correlationId` | String | No | Correlation ID de la transacción. |
| `actor` | String | No | Usuario o sistema que gatilló el evento. |
| `payload` | Object | Sí | Carga útil del evento de dominio. |

### 6.3. Eventos Emitidos por el Dominio
1. **`ProductionStatusChanged` (Procesado por Report):**
   - `payload.productionId`: Long
   - `payload.organizerId`: String (ignorado por Report)
   - `payload.productionName`: String (ignorado por Report)
   - `payload.previousStatus`: String (SOLICITADO, CONFIRMADO, EN_MONTAJE, EN_EJECUCION, CERRADO, CANCELADO)
   - `payload.newStatus`: String (SOLICITADO, CONFIRMADO, EN_MONTAJE, EN_EJECUCION, CERRADO, CANCELADO)
   - `payload.scheduledAt`: String (ignorado por Report)
   - `payload.location`: String (ignorado por Report)
   - `payload.occurredAt`: Instant (ISO-8601 UTC)

2. **`ProductionCreated` (Ignorado por Report en Implementación Actual):**
   - Emitido al crearse una producción (`status="SOLICITADO"`).
   - **Comportamiento en Report:** El deserializador y mapeador `ProductionEventMapper` verifica `type`. Si es distinto de `ProductionStatusChanged`, registra un log informativo (`INFO`) y descarta el evento de forma segura sin arrojar error ni enviarlo a la DLT. Esto se debe a que el modelo analítico actual computa transiciones de estado observadas.

### 6.4. Compatibilidad Contractual vs. Integración E2E
- **Compatibilidad Contractual (Validada):** Report dispone del deserializador tolerante (Jackson con `FAIL_ON_UNKNOWN_PROPERTIES=false`), validación estricta de tipos de `ProductionStatusChanged`, filtro selectivo de `ProductionCreated`, inserción idempotente con `ON CONFLICT DO NOTHING`, y enrutamiento a DLT para mensajes corruptos. Validado con Testcontainers (PostgreSQL + Kafka).
- **Integración E2E (Diferida):** La ejecución integrada en vivo con el clúster Kafka de AWS y el emisor de Producciones corresponde al sprint de integración E2E (EMX-78).

---

## 7. Prevención de Colisión de Puertos Locales

En entornos locales Docker Compose:
- `ms-eventomax-notify` mapea el puerto host `8083:8080`.
- Para evitar colisiones de puerto en la máquina host de desarrollo sin alterar la configuración de Notify, `ms-eventomax-report` mapea el puerto host configurable:
  ```yaml
  ports:
    - "${REPORT_PORT:-8085}:8080"
  ```
- En `.env.example`: `REPORT_PORT=8085`.
- En la red interna de contenedores (`eventomax-net`), el servicio sigue escuchando en el puerto HTTP estándar `8080`.
