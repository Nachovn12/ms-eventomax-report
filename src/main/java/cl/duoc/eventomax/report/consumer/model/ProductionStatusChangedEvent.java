package cl.duoc.eventomax.report.consumer.model;

import cl.duoc.eventomax.report.model.enums.ProductionStatus;

import java.time.Instant;
import java.util.UUID;

public record ProductionStatusChangedEvent(
        UUID eventId,
        String type,
        Long productionId,
        ProductionStatus previousStatus,
        ProductionStatus newStatus,
        Instant occurredAt,
        String traceId,
        String correlationId
) {}

