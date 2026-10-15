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
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
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

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");

        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "report-integration-group");
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        registry.add("KAFKA_LISTENER_AUTO_STARTUP", () -> "true");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("app.kafka.retry.backoff-interval-ms", () -> "200");
        registry.add("app.kafka.retry.max-attempts", () -> "2");
    }

    @Autowired
    private KafkaTemplate<String, String> dltKafkaTemplate;

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
        for (var container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            if (!container.isRunning()) {
                container.start();
            }
        }
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

        dltKafkaTemplate.send("productions.events", String.valueOf(productionId), validV1Payload);

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
        dltKafkaTemplate.send("productions.events", String.valueOf(productionId), eventJson);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.existsById(duplicateEventId)).isTrue();
        });

        long initialFactCount = reportProductionFactRepository.count();

        // Reenvío del mismo mensaje (duplicado)
        dltKafkaTemplate.send("productions.events", String.valueOf(productionId), eventJson);

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
        dltKafkaTemplate.send("productions.events", String.valueOf(productionId), newerEvent);

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
        dltKafkaTemplate.send("productions.events", String.valueOf(productionId), olderEvent);

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

        dltKafkaTemplate.send("productions.events", String.valueOf(productionId), createdEvent);

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

        dltKafkaTemplate.send("productions.events", "invalid-key", invalidJsonPayload);

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
}
