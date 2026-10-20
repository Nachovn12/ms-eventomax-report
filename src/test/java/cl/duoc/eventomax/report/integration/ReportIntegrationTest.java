package cl.duoc.eventomax.report.integration;

import cl.duoc.eventomax.report.model.ReportProductionFact;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import cl.duoc.eventomax.report.repository.ProcessedEventRepository;
import cl.duoc.eventomax.report.repository.ReportProductionFactRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ReportIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ReportIntegrationTest.class);

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("eventomax_report")
            .withUsername("testuser")
            .withPassword("testpass");

    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    static Consumer<String, String> dltConsumer;

    static {
        postgres.start();
        kafka.start();
    }

    private static void assertEphemeralContainersIsolatedBeforeContext() {
        if (!postgres.isRunning() || !kafka.isRunning()) {
            throw new IllegalStateException("Testcontainers PostgreSQL y Kafka deben estar en ejecución antes de iniciar el contexto Spring.");
        }
        String ephemeralBootstrap = kafka.getBootstrapServers();
        if (ephemeralBootstrap == null || ephemeralBootstrap.isBlank()
                || ephemeralBootstrap.contains(":9092") || ephemeralBootstrap.contains(":29092")) {
            throw new IllegalStateException("Aislamiento Kafka violado: bootstrap-servers efímero inválido o apunta al puerto persistente 9092: " + ephemeralBootstrap);
        }
        String jdbcUrl = postgres.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()
                || jdbcUrl.contains(":5432/") || jdbcUrl.contains(":5433/")
                || jdbcUrl.contains(":5434/") || jdbcUrl.contains(":5435/")) {
            throw new IllegalStateException("Aislamiento PostgreSQL violado: JDBC URL apunta a un puerto de base persistente: " + jdbcUrl);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        assertEphemeralContainersIsolatedBeforeContext();

        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USER", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");

        registry.add("KAFKA_BOOTSTRAP_SERVERS", kafka::getBootstrapServers);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.consumer.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.producer.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.admin.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("spring.kafka.consumer.group-id", () -> "report-integration-group");
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        registry.add("KAFKA_CONSUMER_AUTO_STARTUP", () -> "true");
        registry.add("KAFKA_LISTENER_AUTO_STARTUP", () -> "true");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("app.kafka.retry.backoff-interval-ms", () -> "200");
        registry.add("app.kafka.retry.max-attempts", () -> "2");
    }

    @Autowired
    private KafkaTemplate<String, String> dltKafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private Environment environment;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ReportProductionFactRepository reportProductionFactRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void setupKafka() throws Exception {
        assertEphemeralContainersIsolatedBeforeContext();
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic("productions.events", 3, (short) 1),
                    new NewTopic("productions.events.report.DLT", 3, (short) 1)
            )).all().get(15, TimeUnit.SECONDS);
        }

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        dltConsumer = new KafkaConsumer<>(props);
        dltConsumer.subscribe(Collections.singletonList("productions.events.report.DLT"));
    }

    @BeforeEach
    void ensureListenerStarted() {
        verifyEffectiveKafkaAndDatabaseIsolation();
        for (var container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            if (!container.isRunning()) {
                container.start();
            }
        }
    }

    private void verifyEffectiveKafkaAndDatabaseIsolation() {
        assertEphemeralContainersIsolatedBeforeContext();
        String expectedBootstrap = kafka.getBootstrapServers();

        String envBootstrap = environment.getProperty("spring.kafka.bootstrap-servers");
        String envConsumerBootstrap = environment.getProperty("spring.kafka.consumer.bootstrap-servers");
        String envProducerBootstrap = environment.getProperty("spring.kafka.producer.bootstrap-servers");
        String envAdminBootstrap = environment.getProperty("spring.kafka.admin.bootstrap-servers");

        Object producerBootstrap = dltKafkaTemplate.getProducerFactory()
                .getConfigurationProperties()
                .get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
        Object consumerBootstrap = consumerFactory
                .getConfigurationProperties()
                .get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);

        assertThat(envBootstrap).isEqualTo(expectedBootstrap).doesNotContain(":9092");
        assertThat(envConsumerBootstrap).isEqualTo(expectedBootstrap).doesNotContain(":9092");
        assertThat(envProducerBootstrap).isEqualTo(expectedBootstrap).doesNotContain(":9092");
        assertThat(envAdminBootstrap).isEqualTo(expectedBootstrap).doesNotContain(":9092");
        assertThat(String.valueOf(producerBootstrap)).contains(expectedBootstrap).doesNotContain(":9092");
        assertThat(String.valueOf(consumerBootstrap)).contains(expectedBootstrap).doesNotContain(":9092");
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo(postgres.getJdbcUrl())
                .doesNotContain(":5432/")
                .doesNotContain(":5433/")
                .doesNotContain(":5434/");
    }

    private void publishToIsolatedKafka(String topic, String key, String payload) {
        verifyEffectiveKafkaAndDatabaseIsolation();
        dltKafkaTemplate.send(topic, key, payload);
    }

    @AfterAll
    static void tearDown() {
        if (dltConsumer != null) {
            dltConsumer.close();
        }
    }

    @Test
    @DisplayName("Debe verificar esquema real PostgreSQL con Flyway V1 y ddl-auto=validate")
    void testFlywayAndRealPostgresSchemaValidation() {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(kafka.isRunning()).isTrue();

        UUID testEventId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            int inserted = processedEventRepository.insertIfNotExists(testEventId, Instant.now());
            assertThat(inserted).isEqualTo(1);
        });
        assertThat(processedEventRepository.existsById(testEventId)).isTrue();
    }

    @Test
    @DisplayName("Debe procesar evento ProductionStatusChanged v1, persistir facts e impactar endpoint /kpis")
    void testConsumeProductionStatusChangedV1ContractAndQueryKpis() throws Exception {
        UUID eventId = UUID.randomUUID();
        long productionId = 1001L;
        Instant occurredAt = Instant.now().minusSeconds(60);

        String validV1Payload = String.format("""
            {
              "schemaVersion": "1.0",
              "eventId": "%s",
              "type": "ProductionStatusChanged",
              "timestamp": "%s",
              "traceId": "trace-tc-1",
              "correlationId": "corr-tc-1",
              "actor": "admin-user",
              "payload": {
                "productionId": %d,
                "organizerId": "org-valeria",
                "productionName": "Concierto Primavera",
                "previousStatus": "SOLICITADO",
                "newStatus": "CONFIRMADO",
                "scheduledAt": "2026-11-20T21:00:00",
                "location": "Movistar Arena",
                "occurredAt": "%s"
              }
            }
            """, eventId, Instant.now(), productionId, occurredAt);

        publishToIsolatedKafka("productions.events", String.valueOf(productionId), validV1Payload);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.existsById(eventId)).isTrue();
            List<ReportProductionFact> facts = reportProductionFactRepository.findAll();
            assertThat(facts).anyMatch(f -> f.getEventId().equals(eventId) && f.getNewStatus() == ProductionStatus.CONFIRMADO);
        });

        mockMvc.perform(get("/api/report/kpis?range=last24h")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_Admin"),
                                new SimpleGrantedAuthority("SCOPE_access_as_user")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("last24h"))
                .andExpect(jsonPath("$.metrics.currentStatusDistribution.CONFIRMADO").exists())
                .andExpect(jsonPath("$.partialCoverage.isPartial").value(true))
                .andExpect(jsonPath("$.partialCoverage.description").isNotEmpty());
    }

    @Test
    @DisplayName("Debe garantizar inserción idempotente ante eventos duplicados (mismo eventId)")
    void testIdempotencyOnDuplicateEvents() {
        UUID duplicateEventId = UUID.randomUUID();
        long productionId = 1002L;
        Instant occurredAt = Instant.now().minusSeconds(120);

        String eventJson = String.format("""
            {
              "schemaVersion": "1.0",
              "eventId": "%s",
              "type": "ProductionStatusChanged",
              "timestamp": "%s",
              "traceId": "trace-dup-1",
              "correlationId": "corr-dup-1",
              "actor": "admin-user",
              "payload": {
                "productionId": %d,
                "organizerId": "org-valeria",
                "productionName": "Festival de Jazz",
                "previousStatus": "CONFIRMADO",
                "newStatus": "EN_MONTAJE",
                "scheduledAt": "2026-11-25T18:00:00",
                "location": "Teatro Caupolican",
                "occurredAt": "%s"
              }
            }
            """, duplicateEventId, Instant.now(), productionId, occurredAt);

        // Envío inicial
        publishToIsolatedKafka("productions.events", String.valueOf(productionId), eventJson);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.existsById(duplicateEventId)).isTrue();
        });

        long initialFactCount = reportProductionFactRepository.count();

        // Reenvío del mismo mensaje (duplicado)
        publishToIsolatedKafka("productions.events", String.valueOf(productionId), eventJson);

        // Dar margen y verificar que no se duplica el hecho
        await().pollDelay(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(reportProductionFactRepository.count()).isEqualTo(initialFactCount);
        });
    }

    @Test
    @DisplayName("Debe resolver estado actual correctamente ante eventos desordenados basándose en occurredAt")
    void testOutOfOrderEventsResolution() {
        long productionId = 1003L;
        Instant t1Earlier = Instant.now().minusSeconds(300);
        Instant t2Later = Instant.now().minusSeconds(100);

        UUID eventId2 = UUID.randomUUID();
        String newerEvent = String.format("""
            {
              "schemaVersion": "1.0",
              "eventId": "%s",
              "type": "ProductionStatusChanged",
              "timestamp": "%s",
              "traceId": "trace-ord-2",
              "payload": {
                "productionId": %d,
                "previousStatus": "CONFIRMADO",
                "newStatus": "EN_EJECUCION",
                "occurredAt": "%s"
              }
            }
            """, eventId2, Instant.now(), productionId, t2Later);

        // Llega primero el evento más nuevo (EN_EJECUCION en t2Later)
        publishToIsolatedKafka("productions.events", String.valueOf(productionId), newerEvent);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.existsById(eventId2)).isTrue();
        });

        UUID eventId1 = UUID.randomUUID();
        String olderEvent = String.format("""
            {
              "schemaVersion": "1.0",
              "eventId": "%s",
              "type": "ProductionStatusChanged",
              "timestamp": "%s",
              "traceId": "trace-ord-1",
              "payload": {
                "productionId": %d,
                "previousStatus": "SOLICITADO",
                "newStatus": "CONFIRMADO",
                "occurredAt": "%s"
              }
            }
            """, eventId1, Instant.now(), productionId, t1Earlier);

        // Llega después el evento más antiguo (CONFIRMADO en t1Earlier)
        publishToIsolatedKafka("productions.events", String.valueOf(productionId), olderEvent);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.existsById(eventId1)).isTrue();
            List<ReportProductionFact> currentStatuses = reportProductionFactRepository.findCurrentStatusOfAllProductions();
            ReportProductionFact current = currentStatuses.stream()
                    .filter(f -> f.getProductionId().equals(productionId))
                    .findFirst()
                    .orElse(null);
            assertThat(current).isNotNull();
            // Debe ser EN_EJECUCION porque occurredAt fue mayor, a pesar de que llegó después
            assertThat(current.getNewStatus()).isEqualTo(ProductionStatus.EN_EJECUCION);
        });
    }

    @Test
    @DisplayName("Debe ignorar evento ProductionCreated sin error ni envío a DLT")
    void testIgnoredProductionCreatedEvent() {
        UUID eventId = UUID.randomUUID();
        long productionId = 1004L;

        String createdEvent = String.format("""
            {
              "schemaVersion": "1.0",
              "eventId": "%s",
              "type": "ProductionCreated",
              "timestamp": "%s",
              "traceId": "trace-ign-1",
              "actor": "organizer",
              "payload": {
                "productionId": %d,
                "organizerId": "org-ign",
                "productionName": "Evento Ignorado",
                "status": "SOLICITADO",
                "scheduledAt": "2026-12-01T20:00:00",
                "location": "Estadio",
                "occurredAt": "%s"
              }
            }
            """, eventId, Instant.now(), productionId, Instant.now());

        publishToIsolatedKafka("productions.events", String.valueOf(productionId), createdEvent);

        // No debe insertarse en processed_event ni en facts
        await().pollDelay(1, TimeUnit.SECONDS).atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.existsById(eventId)).isFalse();
        });
    }

    @Test
    @DisplayName("Debe reenviar eventos no recuperables (inválidos) directamente a DLT")
    void testInvalidEventForwardedToDLT() {
        String invalidJsonPayload = """
            {
              "schemaVersion": "1.0",
              "eventId": "not-a-uuid",
              "type": "ProductionStatusChanged",
              "payload": {
                "productionId": 1005
              }
            }
            """;

        publishToIsolatedKafka("productions.events", "invalid-key", invalidJsonPayload);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, String> records = dltConsumer.poll(Duration.ofMillis(500));
            boolean found = false;
            for (ConsumerRecord<String, String> record : records) {
                if (record.value().contains("not-a-uuid")) {
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        });
    }

    @Test
    @DisplayName("Debe procesar ciclo de vida completo de contrato Productions Outbox (ProductionCreated + ProductionStatusChanged), deduplicar reenvíos e impactar /api/report/kpis")
    void testProductionsOutboxLifecycleContractWithIdempotencyAndKpis() throws Exception {
        long productionId = 91001L;
        String partitionKey = String.valueOf(productionId);

        Instant now = Instant.now();
        Instant tCreated = now.minusSeconds(300);
        Instant tConfirmado = now.minusSeconds(240);
        Instant tEnMontaje = now.minusSeconds(180);
        Instant tEnEjecucion = now.minusSeconds(120);
        Instant tCerrado = now.minusSeconds(60);

        UUID createdEventId = UUID.randomUUID();
        UUID statusEventId1 = UUID.randomUUID();
        UUID statusEventId2 = UUID.randomUUID();
        UUID statusEventId3 = UUID.randomUUID();
        UUID statusEventId4 = UUID.randomUUID();

        long baselineFactCount = reportProductionFactRepository.count();
        long baselineCerradoCount = reportProductionFactRepository.findAll().stream()
                .filter(f -> f.getNewStatus() == ProductionStatus.CERRADO)
                .count();
        long baselineCerradoDistribution = reportProductionFactRepository.findCurrentStatusOfAllProductions().stream()
                .filter(f -> f.getNewStatus() == ProductionStatus.CERRADO)
                .count();

        // 1. Evento ProductionCreated emitido por OutboxRelay de Productions (debe ser ignorado sin error)
        String productionCreatedJson = String.format("""
            {
              "schemaVersion": "1.0",
              "eventId": "%s",
              "type": "ProductionCreated",
              "timestamp": "%s",
              "traceId": "trace-lifecycle-91001",
              "correlationId": "corr-lifecycle-91001",
              "actor": "sub-productor-ep2",
              "payload": {
                "productionId": %d,
                "organizerId": "org-ep2",
                "productionName": "Produccion Contrato Outbox EP2",
                "status": "SOLICITADO",
                "scheduledAt": "2026-11-20T20:00:00.123456",
                "location": "Movistar Arena",
                "occurredAt": "%s"
              }
            }
            """, createdEventId, tCreated, productionId, tCreated);

        // 2. Secuencia causal de transiciones ProductionStatusChanged
        String toConfirmadoJson = buildProductionsOutboxStatusChangedJson(
                statusEventId1, productionId, "SOLICITADO", "CONFIRMADO", tConfirmado);
        String toEnMontajeJson = buildProductionsOutboxStatusChangedJson(
                statusEventId2, productionId, "CONFIRMADO", "EN_MONTAJE", tEnMontaje);
        String toEnEjecucionJson = buildProductionsOutboxStatusChangedJson(
                statusEventId3, productionId, "EN_MONTAJE", "EN_EJECUCION", tEnEjecucion);
        String toCerradoJson = buildProductionsOutboxStatusChangedJson(
                statusEventId4, productionId, "EN_EJECUCION", "CERRADO", tCerrado);

        publishToIsolatedKafka("productions.events", partitionKey, productionCreatedJson);
        publishToIsolatedKafka("productions.events", partitionKey, toConfirmadoJson);
        // Reenvío duplicado del primer cambio de estado (simula at-least-once de OutboxRelay)
        publishToIsolatedKafka("productions.events", partitionKey, toConfirmadoJson);
        publishToIsolatedKafka("productions.events", partitionKey, toEnMontajeJson);
        publishToIsolatedKafka("productions.events", partitionKey, toEnEjecucionJson);
        publishToIsolatedKafka("productions.events", partitionKey, toCerradoJson);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.existsById(statusEventId1)).isTrue();
            assertThat(processedEventRepository.existsById(statusEventId2)).isTrue();
            assertThat(processedEventRepository.existsById(statusEventId3)).isTrue();
            assertThat(processedEventRepository.existsById(statusEventId4)).isTrue();
        });

        // ProductionCreated no debe haberse registrado en processed_event ni en report_production_fact
        assertThat(processedEventRepository.existsById(createdEventId)).isFalse();

        // Exactamente 4 hechos persistidos para productionId=91001L (el duplicado de statusEventId1 fue deduplicado)
        List<ReportProductionFact> prodFacts = reportProductionFactRepository.findAll().stream()
                .filter(f -> f.getProductionId().equals(productionId))
                .toList();
        assertThat(prodFacts).hasSize(4);
        assertThat(reportProductionFactRepository.count()).isEqualTo(baselineFactCount + 4);

        // El estado actual resuelto para 91001L debe ser CERRADO
        ReportProductionFact currentForProduction = reportProductionFactRepository.findCurrentStatusOfAllProductions().stream()
                .filter(f -> f.getProductionId().equals(productionId))
                .findFirst()
                .orElseThrow();
        assertThat(currentForProduction.getNewStatus()).isEqualTo(ProductionStatus.CERRADO);
        assertThat(currentForProduction.getEventId()).isEqualTo(statusEventId4);

        log.info("Verificación aislada Report Kafka OK: bootstrap={}, factsDeProduccion={}, estadoActual={}",
                kafka.getBootstrapServers(), prodFacts.size(), currentForProduction.getNewStatus());

        // Verificar impacto exacto en GET /api/report/kpis?range=last24h
        mockMvc.perform(get("/api/report/kpis?range=last24h")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_Admin"),
                                new SimpleGrantedAuthority("SCOPE_access_as_user")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("last24h"))
                .andExpect(jsonPath("$.partialCoverage.isPartial").value(true))
                .andExpect(jsonPath("$.metrics.totalStatusChanges").value(baselineFactCount + 4))
                .andExpect(jsonPath("$.metrics.closedCount").value(baselineCerradoCount + 1))
                .andExpect(jsonPath("$.metrics.currentStatusDistribution.CERRADO").value(baselineCerradoDistribution + 1));
    }

    private static String buildProductionsOutboxStatusChangedJson(
            UUID eventId, long productionId, String previousStatus, String newStatus, Instant occurredAt) {
        return String.format("""
            {
              "schemaVersion": "1.0",
              "eventId": "%s",
              "type": "ProductionStatusChanged",
              "timestamp": "%s",
              "traceId": "trace-lifecycle-91001",
              "correlationId": "corr-lifecycle-91001",
              "actor": "sub-admin-ep2",
              "payload": {
                "productionId": %d,
                "organizerId": "org-ep2",
                "productionName": "Produccion Contrato Outbox EP2",
                "previousStatus": "%s",
                "newStatus": "%s",
                "scheduledAt": "2026-11-20T20:00:00.123456",
                "location": "Movistar Arena",
                "occurredAt": "%s"
              }
            }
            """, eventId, occurredAt, productionId, previousStatus, newStatus, occurredAt);
    }
}
