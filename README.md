# EventoMax Report Microservice (`ms-eventomax-report`)

Microservicio de reportería, analítica y KPIs del ecosistema EventoMax (Asignatura DSY1107 / Historia EMX-68).

---

## 1. Propósito

El microservicio `ms-eventomax-report` es responsable de construir, mantener y servir el modelo de lectura (read model) optimizado para analítica y reportería del negocio. Consume eventos de dominio asíncronos emitidos por el microservicio de producciones a través de Apache Kafka (`productions.events`), actualiza tablas de hechos analíticos con estricta garantía de idempotencia transaccional y expone endpoints REST de solo lectura para la visualización de indicadores clave (KPIs) por parte de la administración.

---

## 2. Stack Tecnológico

- **Lenguaje:** Java 25 (OpenJDK 25)
- **Framework:** Spring Boot 4.1.1
- **Base de Datos:** PostgreSQL 17 (puerto host `5434`, puerto contenedor `5432`)
- **Migraciones:** Flyway (`flyway-database-postgresql`, validación estricta con `ddl-auto=validate`)
- **Mensajería Asíncrona:** Apache Kafka (`spring-kafka` 4.1.1)
- **Serialización:** Jackson 3 (`tools.jackson.databind.ObjectMapper`)
- **Documentación API:** SpringDoc OpenAPI 3.1.1 (Swagger UI)
- **Métricas y Monitoreo:** Spring Boot Actuator
- **Contenedores:** Docker & Docker Compose

---

## 3. Endpoints de la API REST (Solo Lectura)

Todos los endpoints están bajo el prefijo `/api/report/*`.

| Método | Endpoint | Parámetros Query | Códigos HTTP | Descripción |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/report/kpis` | `range=last24h\|last7d` *(default: `last24h`)* | `200`, `400` | Retorna métricas y KPIs agregados de transiciones de estado en la ventana temporal especificada. |
| `GET` | `/api/report/top-services` | `range=last7d` *(default: `last7d`)* | `501`, `400` | Endpoint diferido. Responde 501 Not Implemented hasta contar con `serviceId` y datos contractuales de servicios. |

### 3.1. Semántica y Reglas de Negocio

- **Ventana temporal en UTC:** Todas las marcas de tiempo se evalúan y generan estrictamente en formato ISO-8601 UTC (`Instant`) bajo la ventana `[now - duración, now]`.
- **KPI 2 (Distribución por Estado Actual):** Acotado exclusivamente al rango temporal evaluado (Opción B aprobada). Se determina el último estado observado de cada `production_id` (criterio: mayor `occurred_at`, desempate por mayor `event_id`). Se listan todos los estados posibles del enum `ProductionStatus` (`SOLICITADO`, `CONFIRMADO`, `EN_MONTAJE`, `EN_EJECUCION`, `CERRADO`, `CANCELADO`), inicializando en `0` aquellos sin ocurrencias.
- **Cobertura Parcial:** Dado que el microservicio únicamente ingesta eventos de transición de estado (`ProductionStatusChanged`), la respuesta incluye explícitamente el objeto `partialCoverage` indicando que los indicadores reflejan cambios de estado observados y no constituyen el inventario maestro ni total de producciones del ecosistema.

---

## 4. Ejemplos de Respuesta

### 4.1. `GET /api/report/kpis?range=last24h` (200 OK)
> *(Estructura y nombres de campo propuestos, pendientes de aprobación final por Ignacio)*

```json
{
  "range": "last24h",
  "from": "2026-10-10T16:00:00Z",
  "to": "2026-10-11T16:00:00Z",
  "generatedAt": "2026-10-11T16:00:00Z",
  "partialCoverage": {
    "isPartial": true,
    "description": "Métricas calculadas exclusivamente sobre eventos de transición de estado (ProductionStatusChanged). No representa el inventario total de producciones del sistema ni incluye producciones sin cambios de estado en el rango."
  },
  "metrics": {
    "totalStatusChanges": 15,
    "currentStatusDistribution": {
      "SOLICITADO": 4,
      "CONFIRMADO": 3,
      "EN_MONTAJE": 2,
      "EN_EJECUCION": 3,
      "CERRADO": 2,
      "CANCELADO": 1
    },
    "transitions": [
      {
        "previousStatus": "SOLICITADO",
        "newStatus": "CONFIRMADO",
        "count": 5
      },
      {
        "previousStatus": "CONFIRMADO",
        "newStatus": "EN_MONTAJE",
        "count": 4
      },
      {
        "previousStatus": "EN_MONTAJE",
        "newStatus": "EN_EJECUCION",
        "count": 3
      },
      {
        "previousStatus": "EN_EJECUCION",
        "newStatus": "CERRADO",
        "count": 2
      },
      {
        "previousStatus": "SOLICITADO",
        "newStatus": "CANCELADO",
        "count": 1
      }
    ],
    "cancelledCount": 1,
    "closedCount": 2
  }
}
```

### 4.2. `GET /api/report/kpis?range=invalid` (400 Bad Request)
Formato estándar ProblemDetail (RFC 9457) con lista de valores permitidos:

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

### 4.3. `GET /api/report/top-services` (501 Not Implemented)
Formato estándar ProblemDetail (RFC 9457):

```json
{
  "type": "about:blank",
  "title": "Funcionalidad no implementada",
  "status": 501,
  "detail": "El reporte de top-services está diferido: requiere serviceId y datos reales de contratación de servicios no disponibles en el contrato actual.",
  "instance": "/api/report/top-services"
}
```

---

## 5. Read Model y Persistencia

El modelo de lectura está normalizado para optimizar agregaciones analíticas e implementado en PostgreSQL a través de migraciones Flyway (`src/main/resources/db/migration/V1__create_report_schema.sql`). La aplicación valida el esquema contra JPA al iniciar (`spring.jpa.hibernate.ddl-auto=validate`).

### 5.1. Tablas

1. **`processed_event` (Control de Idempotencia):**
   - `event_id UUID PRIMARY KEY`: Identificador único del evento recibido en el envelope.
   - `processed_at TIMESTAMPTZ NOT NULL`: Fecha/hora de procesamiento del evento.
   - *Mecanismo atómico:* Inserción nativa `INSERT INTO processed_event (event_id, processed_at) VALUES (:eventId, :processedAt) ON CONFLICT DO NOTHING`. Si retorna `0`, el evento ya existía y es ignorado sin duplicar datos; si retorna `1`, se inserta el hecho en la misma transacción (`@Transactional`).

2. **`report_production_fact` (Hechos de Transiciones):**
   Contiene **únicamente** las columnas aprobadas por el contrato del read model:
   - `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`: Clave subrogada autoincremental.
   - `event_id UUID NOT NULL UNIQUE REFERENCES processed_event(event_id)`: Clave foránea al evento procesado.
   - `type VARCHAR(50) NOT NULL`: Tipo de evento (`CHECK (type = 'ProductionStatusChanged')`).
   - `production_id BIGINT NOT NULL`: Identificador numérico de la producción.
   - `previous_status VARCHAR(20) NOT NULL`: Estado anterior (`CHECK` restringido a los 6 estados válidos).
   - `new_status VARCHAR(20) NOT NULL`: Estado nuevo (`CHECK` restringido a los 6 estados válidos).
   - `occurred_at TIMESTAMPTZ NOT NULL`: Marca temporal en que ocurrió el cambio (usada para filtros de rango temporal).

3. **Índices de optimización:**
   - `idx_report_fact_occurred_at` sobre `(occurred_at)`
   - `idx_report_fact_prod_occurred` sobre `(production_id, occurred_at DESC)`
   - `idx_report_fact_transitions` sobre `(previous_status, new_status, occurred_at)`

---

## 6. Integración Kafka y Consumo de Eventos

- **Topic principal:** `productions.events` (configurable vía `KAFKA_TOPIC_PRODUCTION_EVENTS`).
- **Consumer Group:** `report-group` (configurable vía `KAFKA_CONSUMER_GROUP`).
- **Contrato Kafka (v0.1 provisional):** Envelope JSON con campos raíz `type`, `eventId`, `timestamp`, `traceId`, `correlationId` y objeto anidado `payload` (`productionId`, `previousStatus`, `newStatus`, `occurredAt`, etc.).
- **Filtrado tolerante:**
  - Deserialización tolerante con Jackson 3 (`FAIL_ON_UNKNOWN_PROPERTIES=false`), ignorando campos no requeridos por reportería (`organizerId`, `productionName`, `location`, `scheduledAt`).
  - Eventos cuyo `type` sea distinto de `ProductionStatusChanged` (como un futuro `ProductionCreated`) son ignorados de forma segura con log a nivel `INFO`.
- **Manejo de Errores y Dead Letter Topic (DLT):**
  - **Topic DLT:** `productions.events.report.DLT` (*PROPUESTA* de Ignacio a confirmar en EMX-71, configurable vía `KAFKA_TOPIC_REPORT_DLT`).
  - **Reintentos:** `DefaultErrorHandler` configurado con 3 intentos máximos y backoff fijo de 1000 ms.
  - **Errores no reintentables:** `NonRetryableEventException` (validación de campos obligatorios, estado desconocido), `JacksonException` (JSON malformado) y `IllegalArgumentException` se desvían de inmediato a la DLT sin consumir reintentos.
  - **Publicador DLT:** `DeadLetterPublishingRecoverer` respaldado por un bean dedicado `KafkaTemplate<String, String>` (`StringSerializer` en clave y valor) para garantizar que el mensaje original se preserve íntegro en la DLT.
- **Trazabilidad:** Extracción de `traceId` y `correlationId` desde el envelope a MDC durante la invocación del listener, con limpieza garantizada en bloque `finally`.

---

## 7. Variables de Entorno

Variables leídas desde `application.yml` y documentadas en `.env.example`:

| Variable | Descripción | Valor por Defecto / Ejemplo | Declarado en |
| :--- | :--- | :--- | :--- |
| `POSTGRES_DB` | Nombre de la base de datos PostgreSQL | `eventomax_report` | `docker-compose.yml`, `.env.example` |
| `DB_URL` | URL JDBC de conexión a PostgreSQL | `jdbc:postgresql://localhost:5434/eventomax_report` | `application.yml`, `.env.example` |
| `DB_USER` | Usuario de autenticación PostgreSQL | `your_db_user` | `application.yml`, `.env.example` |
| `DB_PASSWORD` | Contraseña de autenticación PostgreSQL | `your_db_password` | `application.yml`, `.env.example` |
| `KAFKA_BOOTSTRAP_SERVERS` | Direcciones de los brokers de Apache Kafka | `localhost:9092` | `application.yml`, `.env.example` |
| `KAFKA_CONSUMER_GROUP` | Grupo consumidor de Kafka para reportería | `report-group` | `application.yml`, `.env.example` |
| `KAFKA_LISTENER_AUTO_STARTUP` | Auto-arranque del listener de Kafka (`false` en tests) | `true` | `application.yml`, `.env.example` |
| `KAFKA_TOPIC_PRODUCTION_EVENTS` | Topic de eventos entrantes de producciones | `productions.events` | `application.yml`, `.env.example` |
| `KAFKA_TOPIC_REPORT_DLT` | Topic de Dead Letter Queue para eventos erróneos | `productions.events.report.DLT` | `application.yml`, `.env.example` |
| `KAFKA_RETRY_MAX_ATTEMPTS` | Número máximo de intentos antes de enviar a DLT | `3` | `application.yml`, `.env.example` |
| `KAFKA_RETRY_BACKOFF_MS` | Tiempo de espera entre reintentos (milisegundos) | `1000` | `application.yml`, `.env.example` |

---

## 8. Pruebas Automatizadas

La suite de pruebas automatizadas utiliza la base de datos en memoria H2 en modo compatibilidad PostgreSQL (`MODE=PostgreSQL;DB_CLOSE_DELAY=-1`), ejecutando las migraciones Flyway reales y con `spring.kafka.listener.auto-startup: false` para correr de manera aislada sin requerir brokers Kafka externos.

Para ejecutar los tests:

```powershell
# Windows
.\mvnw.cmd clean test

# Linux / macOS
./mvnw clean test
```

La suite cubre:
- Validación de esquema Flyway y restricciones SQL (`CHECK`, `UNIQUE`, `NOT NULL`, FK).
- Idempotencia transaccional y eventos fuera de orden en `ProcessedEventRepository` y `ProductionEventIngestionService`.
- Lógica de cálculo de KPIs agregados con hora fija (`Clock.fixed`) y distribución completa de estados.
- Deserialización tolerante con Jackson 3 y validación de tipos desconocidos en `ProductionEventMapper`.
- Propagación y limpieza de MDC en `ProductionEventListener`.
- Clasificación de excepciones reintentables y no reintentables en `KafkaConsumerConfigTest`.
- Endpoints REST con MockMvc para códigos 200, 400 y 501.

---

## 9. Despliegue con Docker y Docker Compose
> **IMPORTANTE ANTES DE LEVANTAR CON DOCKER:** 
> Debes copiar el archivo `.env.example` a `.env` (`Copy-Item .env.example .env` en PowerShell) y completar `DB_USER` y `DB_PASSWORD` con valores locales de prueba. Sin este archivo, PostgreSQL no iniciará. El archivo `.env` está en `.gitignore` y no se versiona por seguridad.

### 9.1. Puertos Asignados

| Servicio | Contenedor | Puerto Host | Puerto Contenedor |
| :--- | :--- | :--- | :--- |
| `postgres` | `eventomax-report-db` | `5434` | `5432` |
| `report` | `eventomax-report-app` | `8083` | `8080` |

### 9.2. Comandos de Gestión

```bash
# 1. Validar la sintaxis y variables de Docker Compose
docker compose config

# 2. Construir la imagen y levantar los servicios en segundo plano
docker compose up -d --build

# 3. Verificar el estado de los contenedores
docker compose ps

# 4. Comprobar salud del microservicio
curl http://localhost:8083/actuator/health

# 5. Detener los servicios
docker compose down
```

---

## 10. Documentación OpenAPI y Actuator

Con la aplicación en ejecución:

- **Swagger UI:** `http://localhost:8083/swagger-ui/index.html` (o `http://localhost:8083/swagger-ui.html`)
- **Especificación OpenAPI (JSON):** `http://localhost:8083/v3/api-docs`
- **Health Check (Actuator):** `http://localhost:8083/actuator/health`
- **Info (Actuator):** `http://localhost:8083/actuator/info`

---

## 11. Seguridad

> **PENDIENTE de EMX-121 (trust boundary con BFF). Report no debe exponerse públicamente. Rol funcional: Admin.**

- **Trust Boundary:** Siguiendo la definición arquitectónica aprobada por Ignacio Valeria, `ms-eventomax-report` funcionará como un OAuth2 Resource Server validando tokens JWT Bearer propagados exclusivamente por el BFF (Edge Service).
- **Control de Acceso:** La autenticación y la validación del rol funcional **Admin** se resuelven en el BFF. El microservicio Report opera en red interna y no debe exponerse públicamente al tráfico de internet.

---

## 12. Pendientes y Decisiones Abiertas del Contrato

1. **Aprovisionamiento de Topics (EMX-71):** Creación formal de los topics `productions.events` y `productions.events.report.DLT` en el cluster Kafka junto con Ignacio.
2. **Confirmación de Nombre DLT:** El nombre `productions.events.report.DLT` es una **PROPUESTA** de Ignacio a formalizar en EMX-71.
3. **Congelamiento de Contrato Kafka v0.1:** El contrato de eventos se mantiene en versión preliminar v0.1 hasta que el equipo de Producciones apruebe y congele el contrato mínimo de eventos (incluyendo el evento futuro `ProductionCreated`).
4. **Nombres de Campos DTO de `/kpis`:** Los nombres del JSON propuesto (`totalStatusChanges`, `currentStatusDistribution`, `transitions`, `cancelledCount`, `closedCount`, `partialCoverage`) están pendientes de aprobación formal por parte de Ignacio.
5. **Reporte `top-services`:** Pendiente hasta contar con el identificador `serviceId` y el modelo de contratación de servicios en el dominio.
6. **Tiempo de Montaje:** Propuesta de cálculo de duración `EN_MONTAJE` -> `EN_EJECUCION` pendiente de confirmación funcional.

## 10. Seguridad y Trust Boundary (EMX-121)

Actualmente, `ms-eventomax-report` opera bajo un modelo de **Trust Boundary** en red interna. 
- El microservicio **no está expuesto públicamente**.
- Se asume que la autenticación, validación del token JWT de Entra ID y autorización de roles (ej. rol `Admin`) son responsabilidad exclusiva del API Gateway y el BFF (`ms-eventomax-bff`).
- Queda pendiente la implementación de OAuth2 Resource Server para validación de firma local (requiere definición de `issuer-uri` por parte de arquitectura).