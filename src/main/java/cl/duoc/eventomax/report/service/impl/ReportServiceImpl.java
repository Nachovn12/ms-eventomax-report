package cl.duoc.eventomax.report.service.impl;

import cl.duoc.eventomax.report.dto.KpiMetricsDto;
import cl.duoc.eventomax.report.dto.KpisReportResponse;
import cl.duoc.eventomax.report.dto.PartialCoverageDto;
import cl.duoc.eventomax.report.dto.TransitionCountDto;
import cl.duoc.eventomax.report.model.ReportProductionFact;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import cl.duoc.eventomax.report.model.enums.ReportRange;
import cl.duoc.eventomax.report.repository.ReportProductionFactRepository;
import cl.duoc.eventomax.report.service.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementación del servicio de reportería de KPIs.
 *
 * Criterio técnico KPI 2 (Opción B):
 * El conteo por estado actual se evalúa acotado al rango temporal solicitado [from, to].
 * Para cada production_id que haya registrado actividad dentro de la ventana temporal,
 * se selecciona su última transición ocurrida en dicha ventana (mayor occurred_at,
 * desempatando determinísticamente por mayor event_id). Aquellas producciones sin
 * eventos en el rango no son consideradas, y todos los estados del enum ProductionStatus
 * se incluyen con valor 0 en caso de no tener ocurrencias.
 */
@Service
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    private static final String PARTIAL_COVERAGE_DESCRIPTION =
            "Métricas calculadas exclusivamente sobre eventos de transición de estado (ProductionStatusChanged). " +
            "No representa el inventario total de producciones del sistema ni incluye producciones sin cambios de estado en el rango.";

    private final ReportProductionFactRepository factRepository;
    private final Clock clock;

    public ReportServiceImpl(ReportProductionFactRepository factRepository, Clock clock) {
        this.factRepository = factRepository;
        this.clock = clock;
    }

    @Override
    public KpisReportResponse calculateKpis(ReportRange range) {
        Instant to = clock.instant();
        Instant from = to.minus(range.getDuration());
        Instant generatedAt = to;

        long totalStatusChanges = factRepository.countByOccurredAtBetween(from, to);
        long cancelledCount = factRepository.countByNewStatusAndOccurredAtBetween(ProductionStatus.CANCELADO, from, to);
        long closedCount = factRepository.countByNewStatusAndOccurredAtBetween(ProductionStatus.CERRADO, from, to);

        List<TransitionCountDto> transitions = factRepository.findTransitionsCountBetween(from, to);

        // KPI 2: Conteo por estado actual en el rango (Opción B)
        // Hechos ordenados por productionId, occurredAt DESC y eventId DESC
        List<ReportProductionFact> facts = factRepository.findFactsBetweenOrdered(from, to);

        // Toma el primer registro por productionId (Streams)
        Map<Long, ReportProductionFact> latestFactByProduction = facts.stream()
                .collect(Collectors.toMap(
                        ReportProductionFact::getProductionId,
                        fact -> fact,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));

        // Inicializa todos los estados del enum en 0
        Map<ProductionStatus, Long> currentStatusDistribution = new LinkedHashMap<>();
        for (ProductionStatus status : ProductionStatus.values()) {
            currentStatusDistribution.put(status, 0L);
        }

        // Acumula el conteo del último estado observado para cada producción del rango
        for (ReportProductionFact latestFact : latestFactByProduction.values()) {
            currentStatusDistribution.compute(latestFact.getNewStatus(), (status, currentCount) ->
                    (currentCount == null ? 0L : currentCount) + 1L);
        }

        PartialCoverageDto partialCoverage = new PartialCoverageDto(true, PARTIAL_COVERAGE_DESCRIPTION);
        KpiMetricsDto metrics = new KpiMetricsDto(
                totalStatusChanges,
                currentStatusDistribution,
                transitions,
                cancelledCount,
                closedCount
        );

        return new KpisReportResponse(
                range.getValue(),
                from,
                to,
                generatedAt,
                partialCoverage,
                metrics
        );
    }
}

