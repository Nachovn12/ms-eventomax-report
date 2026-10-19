package cl.duoc.eventomax.report.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductionEventPayloadDto(
        Long productionId,
        String organizerId,
        String productionName,
        String previousStatus,
        String newStatus,
        String scheduledAt,
        String location,
        String occurredAt,
        List<ContractedServiceEventDto> services
) {}

