package cl.duoc.eventomax.report.service;

import cl.duoc.eventomax.report.dto.KpisReportResponse;
import cl.duoc.eventomax.report.model.ReportProductionFact;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import cl.duoc.eventomax.report.model.enums.ReportRange;
import cl.duoc.eventomax.report.repository.ProcessedEventRepository;
import cl.duoc.eventomax.report.repository.ReportProductionFactRepository;
import cl.duoc.eventomax.report.service.impl.ReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

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

    private ReportService reportService;

    // Hora fija de referencia para las pruebas: 2026-10-11T12:00:00Z
    private final Instant fixedNow = Instant.parse("2026-10-11T12:00:00Z");
    private final Clock fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        reportService = new ReportServiceImpl(factRepository, fixedClock);
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
}

