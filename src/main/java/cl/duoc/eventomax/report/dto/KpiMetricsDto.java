package cl.duoc.eventomax.report.dto;

import cl.duoc.eventomax.report.model.enums.ProductionStatus;

import java.util.List;
import java.util.Map;

public record KpiMetricsDto(
        long totalStatusChanges,
        Map<ProductionStatus, Long> currentStatusDistribution,
        List<TransitionCountDto> transitions,
        long cancelledCount,
        long closedCount
) {}

