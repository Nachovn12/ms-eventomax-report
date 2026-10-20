package cl.duoc.eventomax.report.consumer.service;

import cl.duoc.eventomax.report.consumer.model.ContractedServiceEventDto;
import cl.duoc.eventomax.report.consumer.model.ProductionStatusChangedEvent;
import cl.duoc.eventomax.report.model.ReportProductionCancellation;
import cl.duoc.eventomax.report.model.ReportProductionFact;
import cl.duoc.eventomax.report.model.ReportServiceHireFact;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import cl.duoc.eventomax.report.repository.ProcessedEventRepository;
import cl.duoc.eventomax.report.repository.ReportProductionCancellationRepository;
import cl.duoc.eventomax.report.repository.ReportProductionFactRepository;
import cl.duoc.eventomax.report.repository.ReportServiceHireFactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class ProductionEventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ProductionEventIngestionService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final ReportProductionFactRepository reportProductionFactRepository;
    private final ReportServiceHireFactRepository reportServiceHireFactRepository;
    private final ReportProductionCancellationRepository reportProductionCancellationRepository;
    private final Clock clock;

    @Autowired
    public ProductionEventIngestionService(
            ProcessedEventRepository processedEventRepository,
            ReportProductionFactRepository reportProductionFactRepository,
            ReportServiceHireFactRepository reportServiceHireFactRepository,
            ReportProductionCancellationRepository reportProductionCancellationRepository,
            Clock clock) {
        this.processedEventRepository = processedEventRepository;
        this.reportProductionFactRepository = reportProductionFactRepository;
        this.reportServiceHireFactRepository = reportServiceHireFactRepository;
        this.reportProductionCancellationRepository = reportProductionCancellationRepository;
        this.clock = clock;
    }

    public ProductionEventIngestionService(
            ProcessedEventRepository processedEventRepository,
            ReportProductionFactRepository reportProductionFactRepository,
            Clock clock) {
        this(processedEventRepository, reportProductionFactRepository, null, null, clock);
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

        // Manejo de cancelaciones (para excluir del ranking y evitar reactivación fuera de orden)
        if (event.newStatus() == ProductionStatus.CANCELADO) {
            if (reportProductionCancellationRepository != null && !reportProductionCancellationRepository.existsByProductionId(event.productionId())) {
                reportProductionCancellationRepository.saveAndFlush(new ReportProductionCancellation(
                        event.productionId(), event.occurredAt(), event.eventId()));
            }
            if (reportServiceHireFactRepository != null) {
                reportServiceHireFactRepository.markCancelledForProduction(event.productionId(), event.occurredAt());
            }
            log.info("Registrada cancelación de producción para ranking. productionId={}, eventId={}",
                    event.productionId(), event.eventId());
        }

        // Ingestión de hechos de contratación de servicios en CONFIRMADO
        if (event.newStatus() == ProductionStatus.CONFIRMADO && event.services() != null && !event.services().isEmpty() && reportServiceHireFactRepository != null) {
            boolean isProductionCancelled = reportProductionCancellationRepository != null && reportProductionCancellationRepository.existsByProductionId(event.productionId());
            for (ContractedServiceEventDto s : event.services()) {
                if (s.serviceId() != null && !reportServiceHireFactRepository.existsByProductionIdAndServiceId(event.productionId(), s.serviceId())) {
                    ReportServiceHireFact hireFact = new ReportServiceHireFact(
                            event.eventId(),
                            event.productionId(),
                            s.serviceId(),
                            s.serviceName() != null ? s.serviceName() : "Servicio #" + s.serviceId(),
                            s.unitPrice(),
                            event.occurredAt(),
                            isProductionCancelled
                    );
                    if (isProductionCancelled) {
                        hireFact.setCancelledAt(event.occurredAt());
                    }
                    reportServiceHireFactRepository.save(hireFact);
                }
            }
            log.info("Persistidos hechos de contratación de servicios para producción. productionId={}, count={}, isCancelled={}",
                    event.productionId(), event.services().size(), isProductionCancelled);
        }

        log.info("Fact de producción guardado exitosamente. eventId={}, productionId={}, previousStatus={}, newStatus={}, traceId={}",
                event.eventId(), event.productionId(), event.previousStatus(), event.newStatus(), event.traceId());
        return true;
    }
}
