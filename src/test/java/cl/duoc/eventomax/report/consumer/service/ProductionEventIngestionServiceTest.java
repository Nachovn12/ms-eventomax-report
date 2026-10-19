package cl.duoc.eventomax.report.consumer.service;

import cl.duoc.eventomax.report.consumer.model.ProductionStatusChangedEvent;
import cl.duoc.eventomax.report.model.ReportProductionFact;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import cl.duoc.eventomax.report.repository.ProcessedEventRepository;
import cl.duoc.eventomax.report.repository.ReportProductionFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
public class ProductionEventIngestionServiceTest {

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ReportProductionFactRepository reportProductionFactRepository;

    private ProductionEventIngestionService ingestionService;

    private final Instant now = Instant.parse("2026-10-11T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        ingestionService = new ProductionEventIngestionService(
                processedEventRepository,
                reportProductionFactRepository,
                clock
        );
    }

    @Test
    void testIngestNewEventInsertsFactSuccessfully() {
        UUID eventId = UUID.randomUUID();
        ProductionStatusChangedEvent event = new ProductionStatusChangedEvent(
                eventId,
                "ProductionStatusChanged",
                500L,
                ProductionStatus.SOLICITADO,
                ProductionStatus.CONFIRMADO,
                now,
                "trace-1",
                "corr-1"
        );

        boolean result = ingestionService.ingest(event);

        assertThat(result).isTrue();
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        assertThat(reportProductionFactRepository.findAll()).hasSize(1);

        ReportProductionFact fact = reportProductionFactRepository.findAll().get(0);
        assertThat(fact.getEventId()).isEqualTo(eventId);
        assertThat(fact.getProductionId()).isEqualTo(500L);
        assertThat(fact.getPreviousStatus()).isEqualTo(ProductionStatus.SOLICITADO);
        assertThat(fact.getNewStatus()).isEqualTo(ProductionStatus.CONFIRMADO);
        assertThat(fact.getOccurredAt()).isEqualTo(now);
    }

    @Test
    void testIngestDuplicateEventDoesNotDuplicateFact() {
        UUID eventId = UUID.randomUUID();
        ProductionStatusChangedEvent event = new ProductionStatusChangedEvent(
                eventId,
                "ProductionStatusChanged",
                500L,
                ProductionStatus.SOLICITADO,
                ProductionStatus.CONFIRMADO,
                now,
                "trace-1",
                "corr-1"
        );

        // Primer procesamiento
        boolean firstIngest = ingestionService.ingest(event);
        assertThat(firstIngest).isTrue();
        assertThat(reportProductionFactRepository.findAll()).hasSize(1);

        // Segundo procesamiento del MISMO evento
        boolean secondIngest = ingestionService.ingest(event);
        assertThat(secondIngest).isFalse();

        // Debe mantenerse exactamente 1 sola fila en la base de datos
        assertThat(reportProductionFactRepository.findAll()).hasSize(1);
    }

    @Test
    void testIngestOutOfOrderEventsPreservesAllFacts() {
        Long productionId = 600L;
        Instant earlier = Instant.parse("2026-10-11T09:00:00Z");
        Instant later = Instant.parse("2026-10-11T10:00:00Z");

        // Evento que ocurrió más tarde llega PRIMERO
        UUID eventIdLater = UUID.randomUUID();
        ProductionStatusChangedEvent eventLater = new ProductionStatusChangedEvent(
                eventIdLater,
                "ProductionStatusChanged",
                productionId,
                ProductionStatus.CONFIRMADO,
                ProductionStatus.EN_MONTAJE,
                later,
                "trace-later",
                "corr-later"
        );
        ingestionService.ingest(eventLater);

        // Evento que ocurrió más temprano llega DESPUÉS
        UUID eventIdEarlier = UUID.randomUUID();
        ProductionStatusChangedEvent eventEarlier = new ProductionStatusChangedEvent(
                eventIdEarlier,
                "ProductionStatusChanged",
                productionId,
                ProductionStatus.SOLICITADO,
                ProductionStatus.CONFIRMADO,
                earlier,
                "trace-earlier",
                "corr-earlier"
        );
        ingestionService.ingest(eventEarlier);

        // Ambos hechos deben coexistir
        List<ReportProductionFact> allFacts = reportProductionFactRepository.findAll();
        assertThat(allFacts).hasSize(2);

        // La resolución del estado actual debe seguir dando EN_MONTAJE debido a mayor occurredAt
        List<ReportProductionFact> currentStatus = reportProductionFactRepository.findCurrentStatusOfAllProductions();
        assertThat(currentStatus).hasSize(1);
        assertThat(currentStatus.get(0).getNewStatus()).isEqualTo(ProductionStatus.EN_MONTAJE);
    }
}

