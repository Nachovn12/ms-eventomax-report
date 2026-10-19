package cl.duoc.eventomax.report.consumer.service;

import cl.duoc.eventomax.report.consumer.model.ProductionStatusChangedEvent;
import cl.duoc.eventomax.report.model.ReportProductionFact;
import cl.duoc.eventomax.report.repository.ProcessedEventRepository;
import cl.duoc.eventomax.report.repository.ReportProductionFactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class ProductionEventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ProductionEventIngestionService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final ReportProductionFactRepository reportProductionFactRepository;
    private final Clock clock;

    public ProductionEventIngestionService(
            ProcessedEventRepository processedEventRepository,
            ReportProductionFactRepository reportProductionFactRepository,
            Clock clock) {
        this.processedEventRepository = processedEventRepository;
        this.reportProductionFactRepository = reportProductionFactRepository;
        this.clock = clock;
    }

    @Transactional
    public boolean ingest(ProductionStatusChangedEvent event) {
        Instant processedAt = clock.instant();
        int inserted = processedEventRepository.insertIfNotExists(event.eventId(), processedAt);
        if (inserted == 0) {
            log.info("Evento duplicado ignorado por idempotencia. eventId={}, traceId={}, correlationId={}",
                    event.eventId(), event.traceId(), event.correlationId());
            return false;
        }

        ReportProductionFact fact = new ReportProductionFact();
        fact.setEventId(event.eventId());
        fact.setType(event.type());
        fact.setProductionId(event.productionId());
        fact.setPreviousStatus(event.previousStatus());
        fact.setNewStatus(event.newStatus());
        fact.setOccurredAt(event.occurredAt());

        reportProductionFactRepository.save(fact);
        log.info("Fact de producción guardado exitosamente. eventId={}, productionId={}, previousStatus={}, newStatus={}, traceId={}",
                event.eventId(), event.productionId(), event.previousStatus(), event.newStatus(), event.traceId());
        return true;
    }
}

