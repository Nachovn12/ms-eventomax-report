package cl.duoc.eventomax.report.dto;

import cl.duoc.eventomax.report.model.enums.ProductionStatus;

public record TransitionCountDto(
        ProductionStatus previousStatus,
        ProductionStatus newStatus,
        long count
) {
    public TransitionCountDto(ProductionStatus previousStatus, ProductionStatus newStatus, Long count) {
        this(previousStatus, newStatus, count != null ? count : 0L);
    }
}

