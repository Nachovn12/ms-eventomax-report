# EventoMax Report Microservice

## Propósito
Microservicio encargado de generar y proveer acceso al modelo de lectura (read model) de reportes y auditoría del ecosistema EventoMax.
Recibe eventos de otros microservicios mediante Kafka y actualiza su base de datos para consultas optimizadas.

## Estado
**Baseline EMX-70**
> **Nota:** La lógica funcional de negocio, endpoints `/api/report/*`, migraciones Flyway funcionales y consumers Kafka **NO** están implementados en este baseline. Serán desarrollados posteriormente en la tarea **EMX-68**.

## Stack Tecnológico
- Java 25
- Spring Boot 3.4.x / 4.1.1
- PostgreSQL (Base de datos relacional)
- Flyway (Migraciones de base de datos)
- Spring Kafka (Mensajería)
- Docker & Docker Compose

## Variables de Entorno Requeridas
Se requiere configurar las siguientes variables de entorno (ver `.env.example`):
```properties
DB_URL=jdbc:postgresql://localhost:5434/report_db
DB_USER=your_db_user
DB_PASSWORD=your_db_password
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

## Ejecución Local

### 1. Levantar dependencias locales (Base de Datos)
Para iniciar PostgreSQL local para desarrollo:
```bash
docker-compose up -d postgres
```

### 2. Ejecutar Pruebas
El entorno de pruebas está configurado para ejecutarse aisladamente usando H2.
```bash
# Windows
.\mvnw.cmd clean test

# Linux / Mac
./mvnw clean test
```

### 3. Iniciar la Aplicación
```bash
# Windows
.\mvnw.cmd spring-boot:run

# Linux / Mac
./mvnw spring-boot:run
```

## Flujo Git
- `main` = estable / demo
- `develop` = integración
- `feature/EMX-68-*` = desarrollo funcional posterior (diseño de base de datos, consumers, API REST)
