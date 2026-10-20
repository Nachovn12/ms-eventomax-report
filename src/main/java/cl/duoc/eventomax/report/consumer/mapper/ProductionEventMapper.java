package cl.duoc.eventomax.report.consumer.mapper;

import cl.duoc.eventomax.report.consumer.model.ProductionEventEnvelopeDto;
import cl.duoc.eventomax.report.consumer.model.ProductionEventPayloadDto;
import cl.duoc.eventomax.report.consumer.model.ProductionStatusChangedEvent;
import cl.duoc.eventomax.report.exception.NonRetryableEventException;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

@Component
public class ProductionEventMapper {

    private static final Logger log = LoggerFactory.getLogger(ProductionEventMapper.class);
    private static final String EXPECTED_TYPE = "ProductionStatusChanged";

    private final ObjectMapper objectMapper;

    public ProductionEventMapper() {
        this.objectMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public ProductionEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<ProductionStatusChangedEvent> mapAndValidate(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            throw new NonRetryableEventException("Payload de evento Kafka vacío o nulo");
        }

        ProductionEventEnvelopeDto envelope;
        try {
            envelope = objectMapper.readValue(jsonPayload, ProductionEventEnvelopeDto.class);
        } catch (JacksonException e) {
            throw new NonRetryableEventException("Error al deserializar JSON de evento Kafka: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new NonRetryableEventException("Error inesperado al procesar JSON de evento Kafka: " + e.getMessage(), e);
        }

        if (envelope == null) {
            throw new NonRetryableEventException("El envelope deserializado es nulo");
        }

        // Si el type no es ProductionStatusChanged (ej. futuro ProductionCreated), ignorar con log INFO (no es error)
        if (!EXPECTED_TYPE.equalsIgnoreCase(envelope.type())) {
            log.info("Evento con type '{}' ignorado. eventId={}, traceId={}",
                    envelope.type(), envelope.eventId(), envelope.traceId());
            return Optional.empty();
        }

        // Validación estricta de campos obligatorios
        if (envelope.eventId() == null || envelope.eventId().isBlank()) {
            throw new NonRetryableEventException("Campo obligatorio faltante: eventId");
        }
        UUID eventId;
        try {
            eventId = UUID.fromString(envelope.eventId().trim());
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventException("eventId no es un UUID válido: " + envelope.eventId(), e);
        }

        ProductionEventPayloadDto payload = envelope.payload();
        if (payload == null) {
            throw new NonRetryableEventException("Campo obligatorio faltante: payload");
        }

        if (payload.productionId() == null) {
            throw new NonRetryableEventException("Campo obligatorio faltante en payload: productionId");
        }

        if (payload.previousStatus() == null || payload.previousStatus().isBlank()) {
            throw new NonRetryableEventException("Campo obligatorio faltante en payload: previousStatus");
        }
        ProductionStatus previousStatus;
        try {
            previousStatus = ProductionStatus.valueOf(payload.previousStatus().trim());
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventException("Estado inválido en previousStatus: " + payload.previousStatus(), e);
        }

        if (payload.newStatus() == null || payload.newStatus().isBlank()) {
            throw new NonRetryableEventException("Campo obligatorio faltante en payload: newStatus");
        }
        ProductionStatus newStatus;
        try {
            newStatus = ProductionStatus.valueOf(payload.newStatus().trim());
        } catch (IllegalArgumentException e) {
            throw new NonRetryableEventException("Estado inválido en newStatus: " + payload.newStatus(), e);
        }

        if (payload.occurredAt() == null || payload.occurredAt().isBlank()) {
            throw new NonRetryableEventException("Campo obligatorio faltante en payload: occurredAt");
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(payload.occurredAt().trim());
        } catch (DateTimeParseException e) {
            throw new NonRetryableEventException("Formato de fecha inválido en occurredAt (se espera ISO-8601 UTC): " + payload.occurredAt(), e);
        }

        return Optional.of(new ProductionStatusChangedEvent(
                eventId,
                envelope.type(),
                payload.productionId(),
                previousStatus,
                newStatus,
                occurredAt,
                envelope.traceId(),
                envelope.correlationId(),
                payload.services()
        ));
    }
}

