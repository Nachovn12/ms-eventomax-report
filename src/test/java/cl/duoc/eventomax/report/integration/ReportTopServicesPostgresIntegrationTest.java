package cl.duoc.eventomax.report.integration;

import cl.duoc.eventomax.report.model.ReportProductionCancellation;
import cl.duoc.eventomax.report.model.ReportServiceHireFact;
import cl.duoc.eventomax.report.repository.ReportProductionCancellationRepository;
import cl.duoc.eventomax.report.repository.ReportServiceHireFactRepository;
import cl.duoc.eventomax.report.repository.TopServiceProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
public class ReportTopServicesPostgresIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("eventomax_report_top_services")
            .withUsername("testuser")
            .withPassword("testpass");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
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
    }

    @Autowired
    private ReportServiceHireFactRepository serviceHireFactRepository;

    @Autowired
    private ReportProductionCancellationRepository cancellationRepository;

    @BeforeEach
    void cleanUp() {
        serviceHireFactRepository.deleteAll();
        cancellationRepository.deleteAll();
        serviceHireFactRepository.flush();
        cancellationRepository.flush();
    }

    @Test
    @DisplayName("Debe ejecutar findTopServices() en PostgreSQL 15 real con FIRST_VALUE, agrupamiento por serviceId y exclusión de cancelaciones")
    void testFindTopServicesNativePostgresWindowFunctionAndProjections() {
        // Rango de consulta: 2026-10-18T00:00:00Z a 2026-10-18T23:59:59Z
        Instant rangeFrom = Instant.parse("2026-10-18T00:00:00Z");
        Instant rangeTo = Instant.parse("2026-10-18T23:59:59Z");

        Instant t1 = Instant.parse("2026-10-18T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-18T11:00:00Z");
        Instant t3 = Instant.parse("2026-10-18T12:00:00Z");
        Instant t4 = Instant.parse("2026-10-18T14:00:00Z");

        // 1. Servicio 10 (Audio):
        // Prod 101 contrata en t1 con nombre antiguo "Zeta Audio Antiguo"
        serviceHireFactRepository.save(new ReportServiceHireFact(
                UUID.randomUUID(), 101L, 10L, "Zeta Audio Antiguo",
                BigDecimal.valueOf(100.00), t1, false));

        // Prod 102 contrata en t2 con nombre más reciente "Alpha Audio Nuevo"
        serviceHireFactRepository.save(new ReportServiceHireFact(
                UUID.randomUUID(), 102L, 10L, "Alpha Audio Nuevo",
                BigDecimal.valueOf(150.00), t2, false));

        // Prod 103 contrata en t3 con el mismo nombre reciente "Alpha Audio Nuevo"
        serviceHireFactRepository.save(new ReportServiceHireFact(
                UUID.randomUUID(), 103L, 10L, "Alpha Audio Nuevo",
                BigDecimal.valueOf(150.00), t3, false));

        // 2. Empates en hireCount para validar orden determinístico COUNT DESC, serviceId ASC:
        // Servicio 20 y Servicio 30 tendrán exactamente 2 contrataciones cada uno.
        // Prod 201 y 202 contratan Servicio 20 ("Iluminación Pro")
        serviceHireFactRepository.save(new ReportServiceHireFact(
                UUID.randomUUID(), 201L, 20L, "Iluminación Pro",
                BigDecimal.valueOf(200.00), t2, false));
        serviceHireFactRepository.save(new ReportServiceHireFact(
                UUID.randomUUID(), 202L, 20L, "Iluminación Pro",
                BigDecimal.valueOf(200.00), t3, false));

        // Prod 301 y 302 contratan Servicio 30 ("Escenario Modular")
        serviceHireFactRepository.save(new ReportServiceHireFact(
                UUID.randomUUID(), 301L, 30L, "Escenario Modular",
                BigDecimal.valueOf(300.00), t2, false));
        serviceHireFactRepository.save(new ReportServiceHireFact(
                UUID.randomUUID(), 302L, 30L, "Escenario Modular",
                BigDecimal.valueOf(300.00), t3, false));

        // 3. Exclusiones de cancelaciones:
        // Caso A: Fact marcado directamente con is_cancelled = true
        serviceHireFactRepository.save(new ReportServiceHireFact(
                UUID.randomUUID(), 401L, 40L, "Catering Cancelado Directo",
                BigDecimal.valueOf(80.00), t2, true));

        // Caso B: Fact con is_cancelled = false pero producción en report_production_cancellation
        serviceHireFactRepository.save(new ReportServiceHireFact(
                UUID.randomUUID(), 402L, 40L, "Catering Cancelado via Tabla",
                BigDecimal.valueOf(80.00), t2, false));
        cancellationRepository.save(new ReportProductionCancellation(
                402L, t4, UUID.randomUUID()));

        // 4. Respeto del rango temporal:
        // Contratación anterior al rango (t0 = 2026-10-17)
        serviceHireFactRepository.save(new ReportServiceHireFact(
                UUID.randomUUID(), 501L, 50L, "Servicio Pasado",
                BigDecimal.valueOf(50.00), Instant.parse("2026-10-17T23:59:58Z"), false));

        // Contratación posterior al rango (t5 = 2026-10-19)
        serviceHireFactRepository.save(new ReportServiceHireFact(
                UUID.randomUUID(), 502L, 50L, "Servicio Futuro",
                BigDecimal.valueOf(50.00), Instant.parse("2026-10-19T00:00:01Z"), false));

        serviceHireFactRepository.flush();
        cancellationRepository.flush();

        // Ejecución directa de la consulta nativa en PostgreSQL 15
        List<TopServiceProjection> ranking = serviceHireFactRepository.findTopServices(rangeFrom, rangeTo);

        // Verificaciones exhaustivas:
        // A. Cantidad total de filas: exactamente 3 servicios (10, 20, 30).
        // Servicio 40 (cancelado) y Servicio 50 (fuera de rango) no deben aparecer.
        assertThat(ranking).hasSize(3);

        // B. Primer lugar: Servicio 10 con 3 contrataciones
        TopServiceProjection top1 = ranking.get(0);
        assertThat(top1.getServiceId()).isEqualTo(10L);
        assertThat(top1.getHireCount()).isEqualTo(3L);
        // Debe resolver "Alpha Audio Nuevo" (FIRST_VALUE cronológico por contracted_at DESC),
        // superando a "Zeta Audio Antiguo" que es alfabéticamente superior pero anterior en el tiempo.
        assertThat(top1.getServiceName()).isEqualTo("Alpha Audio Nuevo");

        // C. Desempate determinístico ante igual hireCount (hireCount=2):
        // Servicio 20 (id=20) debe preceder a Servicio 30 (id=30) por 'ORDER BY COUNT DESC, service_id ASC'
        TopServiceProjection top2 = ranking.get(1);
        TopServiceProjection top3 = ranking.get(2);

        assertThat(top2.getServiceId()).isEqualTo(20L);
        assertThat(top2.getServiceName()).isEqualTo("Iluminación Pro");
        assertThat(top2.getHireCount()).isEqualTo(2L);

        assertThat(top3.getServiceId()).isEqualTo(30L);
        assertThat(top3.getServiceName()).isEqualTo("Escenario Modular");
        assertThat(top3.getHireCount()).isEqualTo(2L);

        // D. Verificación de que no hay filas duplicadas por serviceId (una única fila por serviceId)
        List<Long> serviceIds = ranking.stream().map(TopServiceProjection::getServiceId).toList();
        assertThat(serviceIds).containsExactly(10L, 20L, 30L);
    }
}
