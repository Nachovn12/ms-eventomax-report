package cl.duoc.eventomax.report.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductionEventEnvelopeDto(
        String type,
        String eventId,
        String timestamp,
        String traceId,
        String correlationId,
        ProductionEventPayloadDto payload
) {}

