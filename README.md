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

El modelo de lectura está normalizado para optimizar agregaciones analíticas e implementado en PostgreSQL a través de migraciones Flyway (`src/main/resources/db/migration/V1__create_report_read_model.sql`). La aplicación valida el esquema contra JPA al iniciar (`spring.jpa.hibernate.ddl-auto=validate`).

### 5.1. Tablas y Claves

1. **`processed_event` (Control de Idempotencia):**
   - `event_id UUID PRIMARY KEY`: Identificador único del evento recibido en la raíz del envelope.
   - `processed_at TIMESTAMP WITH TIME ZONE NOT NULL`: Marca temporal UTC de inserción.
   - *Mecanismo atómico:* Inserción nativa `INSERT INTO processed_event (event_id, processed_at) VALUES (:eventId, :processedAt) ON CONFLICT DO NOTHING`. Si retorna `0`, el evento ya existía y es ignorado sin duplicar datos; si retorna `1`, se inserta el hecho en la misma transacción (`@Transactional`).

2. **`report_production_fact` (Hechos de Transiciones):**
   Contiene **únicamente** las columnas aprobadas por el contrato del read model:
   - `id UUID PRIMARY KEY`: Clave primaria UUID (clave única asignada a cada hecho analítico, no BIGINT).
   - `event_id UUID NOT NULL UNIQUE REFERENCES processed_event(event_id)`: Clave foránea al evento procesado.
   - `type VARCHAR(50) NOT NULL`: Tipo de evento (`ProductionStatusChanged`).
   - `production_id BIGINT NOT NULL`: Identificador numérico de la producción.
   - `previous_status VARCHAR(50) NOT NULL`: Estado anterior (`chk_previous_status` restringido a los 6 estados del dominio).
   - `new_status VARCHAR(50) NOT NULL`: Estado nuevo (`chk_new_status` restringido a los 6 estados del dominio).
   - `occurred_at TIMESTAMP WITH TIME ZONE NOT NULL`: Marca temporal en que ocurrió el cambio (usada para filtros de rango temporal).

3. **Índices de optimización reales:**
   - `idx_fact_occurred_at` sobre `(occurred_at)`
   - `idx_fact_prod_occurred` sobre `(production_id, occurred_at DESC)`
   - `idx_fact_status_occurred` sobre `(new_status, occurred_at)`

---

## 6. Integración Kafka y Consumo de Eventos

- **Topic principal:** `productions.events` (configurable vía `KAFKA_TOPIC_PRODUCTION_EVENTS`).
- **Consumer Group:** `report-group` (configurable vía `KAFKA_CONSUMER_GROUP`).
- **Contrato Kafka (v1.0 oficial de Producciones):** Envelope JSON `KafkaEventEnvelope<T>` con campos raíz:
  - `schemaVersion`: `"1.0"`
  - `eventId`: UUID
  - `type`: String (`ProductionCreated`, `ProductionStatusChanged`)
  - `timestamp`: Instant
  - `traceId`: String
  - `correlationId`: String
  - `actor`: String
  - `payload`: Object
- **Compatibilidad Contractual vs. Integración E2E:**
  - *Compatibilidad Contractual (Validada):* El microservicio implementa deserialización tolerante mediante Jackson (`FAIL_ON_UNKNOWN_PROPERTIES=false`), extrayendo únicamente los campos necesarios y descartando atributos del payload no requeridos por reportería (`organizerId`, `productionName`, `location`, `scheduledAt`).
  - *Integración E2E (Diferida):* La verificación del pipeline distribuido en vivo en clúster AWS corresponde al sprint de integración E2E (EMX-78).
- **Tratamiento de `ProductionCreated`:** Eventos emitidos por Producciones cuyo `type` no corresponda a `ProductionStatusChanged` (como `ProductionCreated`) son ignorados de forma segura con log a nivel `INFO`, sin generar hechos ni desviar a DLT.
- **Manejo de Errores y Dead Letter Topic (DLT):**
  - **Topic DLT:** `productions.events.report.DLT` (configurable vía `KAFKA_TOPIC_REPORT_DLT`).
  - **Reintentos:** `DefaultErrorHandler` configurado con 3 intentos máximos y backoff de 1000 ms.
  - **Errores no reintentables:** `NonRetryableEventException` (validación de campos obligatorios, estado desconocido), `JacksonException` (JSON malformado) y `IllegalArgumentException` se desvían de inmediato a la DLT sin consumir reintentos.
  - **Publicador DLT:** `DeadLetterPublishingRecoverer` respaldado por un bean dedicado `KafkaTemplate<String, String>` para preservar íntegro el mensaje original en la DLT.
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
| `REPORT_PORT` | Puerto host asignado a Report (evita colisión con Notify 8083) | `8085` | `docker-compose.yml`, `.env.example` |
| `KAFKA_BOOTSTRAP_SERVERS` | Direcciones de los brokers de Apache Kafka | `localhost:9092` | `application.yml`, `.env.example` |
| `KAFKA_CONSUMER_GROUP` | Grupo consumidor de Kafka para reportería | `report-group` | `application.yml`, `.env.example` |
| `KAFKA_LISTENER_AUTO_STARTUP` | Auto-arranque del listener de Kafka (`false` en tests aislados) | `true` | `application.yml`, `.env.example` |
| `KAFKA_TOPIC_PRODUCTION_EVENTS` | Topic de eventos entrantes de producciones | `productions.events` | `application.yml`, `.env.example` |
| `KAFKA_TOPIC_REPORT_DLT` | Topic de Dead Letter Queue para eventos erróneos | `productions.events.report.DLT` | `application.yml`, `.env.example` |
| `KAFKA_RETRY_MAX_ATTEMPTS` | Número máximo de intentos antes de enviar a DLT | `3` | `application.yml`, `.env.example` |
| `KAFKA_RETRY_BACKOFF_MS` | Tiempo de espera entre reintentos (milisegundos) | `1000` | `application.yml`, `.env.example` |

---

## 8. Pruebas Automatizadas

La suite de pruebas automatizadas incluye 36 pruebas ejecutables mediante Maven Wrapper:

- **Pruebas Unitarias y de Capa (H2):** 30 pruebas rápidas que validan controladores MockMvc, servicios con reloj fijo (`Clock.fixed`), mapeadores, repositorios y configuración de Kafka.
- **Pruebas de Integración con Testcontainers (PostgreSQL 15 + Kafka 7.4):** 6 pruebas completas que verifican:
  1. Aplicación real de la migración Flyway `V1__create_report_read_model.sql` y validación estricta con `ddl-auto=validate`.
  2. Consumo del contrato real v1.0 `ProductionStatusChanged`, persistencia de hechos en PostgreSQL y consulta exitosa de `/api/report/kpis?range=last24h`.
  3. Inserción idempotente atómica con `ON CONFLICT DO NOTHING` ante eventos duplicados (`eventId` repetido).
  4. Resolución determinista del estado actual de producciones ante eventos desordenados (`occurredAt` cronológico).
  5. Descarte controlado de eventos no suscritos (`ProductionCreated`) sin fallos ni desvío a DLT.
  6. Enrutamiento directo a Dead Letter Topic (`productions.events.report.DLT`) ante eventos no recuperables.

Para ejecutar los tests:

```powershell
# Windows
.\mvnw.cmd clean test

# Linux / macOS
./mvnw clean test
```

---

## 9. Despliegue con Docker y Docker Compose
> **IMPORTANTE ANTES DE LEVANTAR CON DOCKER:**
> Debes copiar el archivo `.env.example` a `.env` (`Copy-Item .env.example .env` en PowerShell) y completar `DB_USER` y `DB_PASSWORD` con valores locales de prueba. Sin este archivo, PostgreSQL no iniciará. El archivo `.env` está en `.gitignore` y no se versiona por seguridad.

### 9.1. Puertos Asignados (Prevención de Colisiones)

| Servicio | Contenedor | Puerto Host | Puerto Contenedor | Notas |
| :--- | :--- | :--- | :--- | :--- |
| `postgres` | `eventomax-report-db` | `5434` | `5432` | Aislado en puerto host 5434 |
| `report` | `eventomax-report-app` | `${REPORT_PORT:-8085}` | `8080` | Configurable vía `REPORT_PORT` (evita colisión con `notify` en puerto 8083) |

### 9.2. Comandos de Gestión

```bash
# 1. Validar la sintaxis y variables de Docker Compose
docker compose config

# 2. Construir la imagen y levantar los servicios en segundo plano
docker compose up -d --build

# 3. Verificar el estado de los contenedores
docker compose ps

# 4. Comprobar salud del microservicio
curl http://localhost:8085/actuator/health

# 5. Detener los servicios
docker compose down
```

---

## 10. Documentación OpenAPI y Actuator

Con la aplicación en ejecución:

- **Swagger UI:** `http://localhost:8085/swagger-ui/index.html` (o `http://localhost:8085/swagger-ui.html`)
- **Especificación OpenAPI (JSON):** `http://localhost:8085/v3/api-docs`
- **Health Check (Actuator):** `http://localhost:8085/actuator/health`
- **Info (Actuator):** `http://localhost:8085/actuator/info`

---

## 11. Seguridad y Trust Boundary (EMX-121)

El microservicio `ms-eventomax-report` opera bajo una arquitectura de **Defensa en Profundidad** y frontera de confianza (**Trust Boundary**) en red interna privada:

### 11.1. Flujo de Autenticación y Autorización E2E
1. **Angular SPA:** El usuario autentica sus credenciales contra **Microsoft Entra ID** (OAuth2 / OIDC) y adquiere un JWT Bearer que contiene los claims de identidad, roles asignados y scopes.
2. **API Gateway:** Enruta perimetralmente el tráfico hacia la red interna sin exponer rutas públicas directas hacia Report.
3. **BFF (`ms-eventomax-bff`):** Actúa como intermediario del frontend y propaga de forma intacta el encabezado `Authorization: Bearer <token>` hacia `REPORT_BASE_URL`.
4. **Report (`ms-eventomax-report`):** Valida de forma autónoma y estricta el token recibido como OAuth2 Resource Server antes de procesar cualquier consulta analítica.

### 11.2. Trust Boundary y Red Interna
- **Aislamiento de Red:** Report reside en la red interna privada de contenedores Docker y **no posee ruta pública en el API Gateway** ni expone puertos al exterior en ambientes productivos (el puerto host local `8085` / `REPORT_PORT` se utiliza exclusivamente para depuración y pruebas locales).
- **Superficie de Exposición Mínima:**
  - Solo permite `GET /api/report/kpis` y `GET /api/report/top-services` con credenciales válidas.
  - No permite ningún método de escritura (`POST`, `PUT`, `PATCH`, `DELETE`).
  - Solo `GET /actuator/health` (y sus subrutas de liveness/readiness) está configurado como `permitAll` para el healthcheck de Docker.
  - Swagger UI (`/swagger-ui/**`), especificación OpenAPI (`/v3/api-docs/**`) y cualquier otro endpoint de Actuator (`/actuator/info`, etc.) quedan denegados.

### 11.3. Claims Exigidos y Reglas de Validación
Para que un token sea considerado válido por Report, debe cumplir:
- **`iss` (Issuer):** Debe coincidir con la URI del emisor de Microsoft Entra ID configurada en `ENTRA_ISSUER_URI`.
- **`aud` (Audience):** Debe contener el identificador de audiencia registrado en `ENTRA_AUDIENCE`.
- **Vigencia Temporal:** No expirado (`exp` y `nbf` válidos).
- **Roles:** El claim `roles` (arreglo JSON de Entra) debe incluir `"Admin"` (mapeado a la autoridad `ROLE_Admin`).
- **Scope:** El claim `scp` (cadena delimitada por espacios de Entra) debe incluir `"access_as_user"` (mapeado a `SCOPE_access_as_user`).
- **Regla Combinada:** Se exige estrictamente `ROLE_Admin` **Y** `SCOPE_access_as_user`.

### 11.4. Variables de Entorno de Seguridad
- `ENTRA_ISSUER_URI`: `https://login.microsoftonline.com/f191bdc8-7926-4722-9ec0-cba663701940/v2.0` (configurable con fallback seguro).
- `ENTRA_AUDIENCE`: `a1a87bcd-fed7-4d65-83c3-338389b80093` (identificador de aplicación de Report en Entra).

### 11.5. Respuestas de Error (RFC 9457 ProblemDetail en Español)
- **HTTP 401 Unauthorized (`title: "No autenticado"`):** Petición sin encabezado `Authorization`, token con firma corrupta, token expirado o audiencia incorrecta.
- **HTTP 403 Forbidden (`title: "Acceso denegado"`):** Token válido perteneciente a otro rol funcional (`Productor`, `Organizador`, `Auditor`), token de administrador sin el scope `access_as_user`, o intento de invocación a métodos no permitidos (`POST`, `PUT`, `PATCH`, `DELETE`).

---

## 12. Pendientes y Decisiones Abiertas del Contrato

1. **Reporte `top-services`:** Pendiente hasta contar con el identificador `serviceId` y el modelo de contratación de servicios en el dominio (Productions solo reserva equipamiento en Catalog).
2. **Tiempo de Montaje:** Propuesta de cálculo de duración `EN_MONTAJE` -> `EN_EJECUCION` pendiente de confirmación funcional.
3. **Formalización de Resource Server en Report (EMX-121):** Presentada propuesta técnica con configuración externa (`ENTRA_ISSUER_URI`, `ENTRA_AUDIENCE`) y matriz de pruebas a la espera de autorización del Tech Leader.
