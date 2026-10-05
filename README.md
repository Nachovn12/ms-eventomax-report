# EventoMax Report Microservice

## Propósito
Microservicio encargado de generar y proveer acceso al modelo de lectura (read model) de reportería, KPIs y analítica del ecosistema EventoMax.
Posteriormente consumirá eventos de otros microservicios mediante Kafka para construir su read model de reportería para consultas optimizadas (actualmente no implementado).

## Estado
**Baseline EMX-70**
> **Nota:** Este es únicamente el baseline técnico inicial (EMX-70). Pertenecen a la tarea funcional **EMX-68**:
> - endpoints `/api/report/*`
> - read model funcional
> - migraciones Flyway funcionales
> - Kafka consumers/listeners
> - lógica de KPIs y top-services

## Stack Tecnológico
- Java 25
- Spring Boot 4.1.1
- PostgreSQL (Base de datos relacional)
- Flyway (Migraciones de base de datos)
- Spring Kafka (Mensajería)
- Docker & Docker Compose

## Variables de Entorno Requeridas
Se requiere configurar las siguientes variables de entorno (ver `.env.example`):
```properties
POSTGRES_DB=eventomax_report
DB_URL=jdbc:postgresql://localhost:5434/eventomax_report
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
