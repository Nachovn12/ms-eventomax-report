package cl.duoc.eventomax.report.service;

import cl.duoc.eventomax.report.dto.KpisReportResponse;
import cl.duoc.eventomax.report.dto.TopServicesReportResponse;
import cl.duoc.eventomax.report.model.ReportProductionCancellation;
import cl.duoc.eventomax.report.model.ReportProductionFact;
import cl.duoc.eventomax.report.model.ReportServiceHireFact;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import cl.duoc.eventomax.report.model.enums.ReportRange;
import cl.duoc.eventomax.report.repository.ProcessedEventRepository;
import cl.duoc.eventomax.report.repository.ReportProductionCancellationRepository;
import cl.duoc.eventomax.report.repository.ReportProductionFactRepository;
import cl.duoc.eventomax.report.repository.ReportServiceHireFactRepository;
import cl.duoc.eventomax.report.service.impl.ReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
public class ReportServiceImplTest {

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ReportProductionFactRepository factRepository;

    @Autowired
    private ReportServiceHireFactRepository serviceHireFactRepository;

    @Autowired
    private ReportProductionCancellationRepository cancellationRepository;

    private ReportService reportService;

    // Hora fija de referencia para las pruebas: 2026-10-11T12:00:00Z
    private final Instant fixedNow = Instant.parse("2026-10-11T12:00:00Z");
    private final Clock fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        reportService = new ReportServiceImpl(factRepository, serviceHireFactRepository, fixedClock);
    }

    private void insertFact(UUID eventId, Long productionId, String type,
                            ProductionStatus previous, ProductionStatus newStatus, Instant occurredAt) {
        processedEventRepository.insertIfNotExists(eventId, Instant.now());
        ReportProductionFact fact = new ReportProductionFact();
        fact.setEventId(eventId);
        fact.setType(type);
        fact.setProductionId(productionId);
        fact.setPreviousStatus(previous);
        fact.setNewStatus(newStatus);
        fact.setOccurredAt(occurredAt);
        factRepository.save(fact);
    }

    @Test
    void testCalculateKpisFiltersByRangeAndAggregatesCorrectly() {
        // Evento 1: Producción 101 dentro de las últimas 24h (2026-10-11T10:00:00Z)
        insertFact(UUID.randomUUID(), 101L, "ProductionStatusChanged",
                ProductionStatus.SOLICITADO, ProductionStatus.CONFIRMADO,
                Instant.parse("2026-10-11T10:00:00Z"));

        // Evento 2: Producción 101 posterior dentro de las 24h (2026-10-11T11:00:00Z)
        insertFact(UUID.randomUUID(), 101L, "ProductionStatusChanged",
                ProductionStatus.CONFIRMADO, ProductionStatus.EN_MONTAJE,
                Instant.parse("2026-10-11T11:00:00Z"));

        // Evento 3: Producción 102 dentro de las 24h -> Cancelada
        insertFact(UUID.randomUUID(), 102L, "ProductionStatusChanged",
                ProductionStatus.SOLICITADO, ProductionStatus.CANCELADO,
                Instant.parse("2026-10-11T09:00:00Z"));

        // Evento 4: Producción 103 fuera del rango de 24h (hace 48h -> 2026-10-09T12:00:00Z)
        insertFact(UUID.randomUUID(), 103L, "ProductionStatusChanged",
                ProductionStatus.SOLICITADO, ProductionStatus.CERRADO,
                Instant.parse("2026-10-09T12:00:00Z"));

        factRepository.flush();

        // Ejecutar KPI para last24h
        KpisReportResponse response = reportService.calculateKpis(ReportRange.LAST_24H);

        assertThat(response.range()).isEqualTo("last24h");
        assertThat(response.to()).isEqualTo(fixedNow);
        assertThat(response.from()).isEqualTo(fixedNow.minusSeconds(86400));
        assertThat(response.partialCoverage().isPartial()).isTrue();
        assertThat(response.partialCoverage().description()).contains("Métricas calculadas exclusivamente");

        // Evento 4 (producción 103) está fuera de 24h, solo 3 eventos dentro de la ventana
        assertThat(response.metrics().totalStatusChanges()).isEqualTo(3L);
        assertThat(response.metrics().cancelledCount()).isEqualTo(1L);
        assertThat(response.metrics().closedCount()).isEqualTo(0L); // Producción 103 no entra en 24h

        // Estados actuales: Prod 101 quedó en EN_MONTAJE, Prod 102 quedó en CANCELADO
        assertThat(response.metrics().currentStatusDistribution().get(ProductionStatus.EN_MONTAJE)).isEqualTo(1L);
        assertThat(response.metrics().currentStatusDistribution().get(ProductionStatus.CANCELADO)).isEqualTo(1L);
        // Estados sin ocurrencias deben estar en 0
        assertThat(response.metrics().currentStatusDistribution().get(ProductionStatus.SOLICITADO)).isEqualTo(0L);
        assertThat(response.metrics().currentStatusDistribution().get(ProductionStatus.CONFIRMADO)).isEqualTo(0L);
        assertThat(response.metrics().currentStatusDistribution().get(ProductionStatus.EN_EJECUCION)).isEqualTo(0L);
        assertThat(response.metrics().currentStatusDistribution().get(ProductionStatus.CERRADO)).isEqualTo(0L);

        // Transiciones
        assertThat(response.metrics().transitions()).hasSize(3);
    }

    @Test
    void testCurrentStatusWithOutOfOrderEventsAndTieBreaker() {
        Long prodId = 200L;
        Instant sameInstant = Instant.parse("2026-10-11T08:00:00Z");

        UUID eventIdOld = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID eventIdNew = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Insertar primero el de mayor ID, luego el de menor ID en el mismo timestamp
        insertFact(eventIdOld, prodId, "ProductionStatusChanged",
                ProductionStatus.SOLICITADO, ProductionStatus.CONFIRMADO, sameInstant);

        insertFact(eventIdNew, prodId, "ProductionStatusChanged",
                ProductionStatus.CONFIRMADO, ProductionStatus.EN_EJECUCION, sameInstant);

        factRepository.flush();

        KpisReportResponse response = reportService.calculateKpis(ReportRange.LAST_24H);

        // El desempate debe favorecer eventIdNew (mayor UUID) -> EN_EJECUCION
        assertThat(response.metrics().currentStatusDistribution().get(ProductionStatus.EN_EJECUCION)).isEqualTo(1L);
        assertThat(response.metrics().currentStatusDistribution().get(ProductionStatus.CONFIRMADO)).isEqualTo(0L);
    }

    private void insertHireFact(UUID eventId, Long productionId, Long serviceId, String serviceName,
                                BigDecimal unitPrice, Instant contractedAt, boolean isCancelled) {
        processedEventRepository.insertIfNotExists(eventId, Instant.now());
        ReportServiceHireFact fact = new ReportServiceHireFact(
                eventId, productionId, serviceId, serviceName, unitPrice, contractedAt, isCancelled
        );
        serviceHireFactRepository.save(fact);
    }

    @Test
    void testCalculateTopServicesRankingAndTieBreaker() {
        Instant t1 = fixedNow.minusSeconds(3600);
        // Prod 101 hires Service 10 and Service 20
        insertHireFact(UUID.randomUUID(), 101L, 10L, "Audio Pro", BigDecimal.valueOf(100), t1, false);
        insertHireFact(UUID.randomUUID(), 101L, 20L, "Iluminación", BigDecimal.valueOf(50), t1, false);

        // Prod 102 hires Service 10
        insertHireFact(UUID.randomUUID(), 102L, 10L, "Audio Pro", BigDecimal.valueOf(100), t1, false);

        // Prod 103 hires Service 20 and Service 30
        insertHireFact(UUID.randomUUID(), 103L, 20L, "Iluminación", BigDecimal.valueOf(50), t1, false);
        insertHireFact(UUID.randomUUID(), 103L, 30L, "Catering", BigDecimal.valueOf(200), t1, false);

        serviceHireFactRepository.flush();

        TopServicesReportResponse response = reportService.calculateTopServices(ReportRange.LAST_24H);

        assertThat(response.range()).isEqualTo("last24h");
        assertThat(response.partialCoverage().isPartial()).isTrue();
        assertThat(response.items()).hasSize(3);

        // Service 10: 2 hires, Service 20: 2 hires. Tie breaker: serviceId ASC -> 10 before 20
        assertThat(response.items().get(0).serviceId()).isEqualTo(10L);
        assertThat(response.items().get(0).serviceName()).isEqualTo("Audio Pro");
        assertThat(response.items().get(0).hireCount()).isEqualTo(2L);

        assertThat(response.items().get(1).serviceId()).isEqualTo(20L);
        assertThat(response.items().get(1).serviceName()).isEqualTo("Iluminación");
        assertThat(response.items().get(1).hireCount()).isEqualTo(2L);

        // Service 30: 1 hire
        assertThat(response.items().get(2).serviceId()).isEqualTo(30L);
        assertThat(response.items().get(2).serviceName()).isEqualTo("Catering");
        assertThat(response.items().get(2).hireCount()).isEqualTo(1L);
    }

    @Test
    void testCalculateTopServicesGroupingByServiceIdAloneWithDeterministicName() {
        Instant t1 = fixedNow.minusSeconds(7200);
        Instant t2 = fixedNow.minusSeconds(3600);

        // Prod 101 contrató Servicio 10 con nombre antiguo "Zeta Audio Antiguo" (alfabéticamente mayor)
        insertHireFact(UUID.randomUUID(), 101L, 10L, "Zeta Audio Antiguo", BigDecimal.valueOf(100), t1, false);
        // Prod 102 contrató Servicio 10 con nombre más reciente "Alpha Audio Nuevo" (alfabéticamente menor)
        insertHireFact(UUID.randomUUID(), 102L, 10L, "Alpha Audio Nuevo", BigDecimal.valueOf(150), t2, false);

        serviceHireFactRepository.flush();

        TopServicesReportResponse response = reportService.calculateTopServices(ReportRange.LAST_24H);

        // No debe fragmentarse en 2 filas; debe agrupar únicamente por serviceId (1 fila)
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).serviceId()).isEqualTo(10L);
        assertThat(response.items().get(0).hireCount()).isEqualTo(2L);
        // Debe retornar "Alpha Audio Nuevo" por ser el más reciente cronológicamente (FIRST_VALUE en contracted_at DESC), no MAX alfabético
        assertThat(response.items().get(0).serviceName()).isEqualTo("Alpha Audio Nuevo");
    }

    @Test
    void testCalculateTopServicesExcludesCancellations() {
        Instant t1 = fixedNow.minusSeconds(3600);
        // Prod 101 hires Service 10
        insertHireFact(UUID.randomUUID(), 101L, 10L, "Audio Pro", BigDecimal.valueOf(100), t1, false);

        // Prod 102 hires Service 10, but is cancelled later
        insertHireFact(UUID.randomUUID(), 102L, 10L, "Audio Pro", BigDecimal.valueOf(100), t1, false);
        // Mark Prod 102 cancelled
        serviceHireFactRepository.markCancelledForProduction(102L, fixedNow.minusSeconds(1800));

        serviceHireFactRepository.flush();

        TopServicesReportResponse response = reportService.calculateTopServices(ReportRange.LAST_24H);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).serviceId()).isEqualTo(10L);
        assertThat(response.items().get(0).hireCount()).isEqualTo(1L); // Only Prod 101
    }

    @Test
    void testCalculateTopServicesExcludesOutOfOrderCancellation() {
        Instant t1 = fixedNow.minusSeconds(3600);
        Long prodId = 104L;

        // Cancellation arrived first and was saved in cancellationRepository
        cancellationRepository.save(new ReportProductionCancellation(prodId, fixedNow.minusSeconds(4000), UUID.randomUUID()));
        cancellationRepository.flush();

        // Later CONFIRMADO arrives with isCancelled=true
        insertHireFact(UUID.randomUUID(), prodId, 10L, "Audio Pro", BigDecimal.valueOf(100), t1, true);
        serviceHireFactRepository.flush();

        TopServicesReportResponse response = reportService.calculateTopServices(ReportRange.LAST_24H);

        // Should be completely excluded, no items in ranking
        assertThat(response.items()).isEmpty();
    }

    @Test
    void testCalculateTopServicesDateRangeFilter() {
        // In range (within 24h)
        insertHireFact(UUID.randomUUID(), 101L, 10L, "Audio Pro", BigDecimal.valueOf(100), fixedNow.minusSeconds(3600), false);
        // Out of range (48h ago)
        insertHireFact(UUID.randomUUID(), 105L, 20L, "Iluminación", BigDecimal.valueOf(50), fixedNow.minusSeconds(48 * 3600), false);

        serviceHireFactRepository.flush();

        TopServicesReportResponse response24h = reportService.calculateTopServices(ReportRange.LAST_24H);
        assertThat(response24h.items()).hasSize(1);
        assertThat(response24h.items().get(0).serviceId()).isEqualTo(10L);

        TopServicesReportResponse response7d = reportService.calculateTopServices(ReportRange.LAST_7D);
        assertThat(response7d.items()).hasSize(2);
    }

    @Test
    void testCalculateTopServicesEmptyWhenNoData() {
        TopServicesReportResponse response = reportService.calculateTopServices(ReportRange.LAST_24H);
        assertThat(response.items()).isNotNull();
        assertThat(response.items()).isEmpty();
        assertThat(response.partialCoverage().isPartial()).isTrue();
    }
}

