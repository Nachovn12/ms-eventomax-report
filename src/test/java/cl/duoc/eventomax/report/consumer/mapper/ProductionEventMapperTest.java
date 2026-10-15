package cl.duoc.eventomax.report.consumer.mapper;

import cl.duoc.eventomax.report.consumer.model.ProductionStatusChangedEvent;
import cl.duoc.eventomax.report.exception.NonRetryableEventException;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ProductionEventMapperTest {

    private ProductionEventMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ProductionEventMapper();
    }

    @Test
    void testMapAndValidateSuccessfulEvent() {
        String json = """
        {
          "type": "ProductionStatusChanged",
          "eventId": "11111111-1111-1111-1111-111111111111",
          "timestamp": "2026-10-11T12:00:00Z",
          "traceId": "22222222-2222-2222-2222-222222222222",
          "correlationId": "33333333-3333-3333-3333-333333333333",
          "payload": {
            "productionId": 100,
            "organizerId": "org-1",
            "productionName": "Concierto Primavera",
            "previousStatus": "SOLICITADO",
            "newStatus": "CONFIRMADO",
            "scheduledAt": "2026-11-01T20:00:00Z",
            "location": "Estadio Nacional",
            "occurredAt": "2026-10-11T12:00:00Z"
          }
        }
        """;

        Optional<ProductionStatusChangedEvent> result = mapper.mapAndValidate(json);

        assertThat(result).isPresent();
        ProductionStatusChangedEvent event = result.get();
        assertThat(event.eventId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(event.type()).isEqualTo("ProductionStatusChanged");
        assertThat(event.productionId()).isEqualTo(100L);
        assertThat(event.previousStatus()).isEqualTo(ProductionStatus.SOLICITADO);
        assertThat(event.newStatus()).isEqualTo(ProductionStatus.CONFIRMADO);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-11T12:00:00Z"));
        assertThat(event.traceId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(event.correlationId()).isEqualTo("33333333-3333-3333-3333-333333333333");
    }

    @Test
    void testMapAndValidateIgnoresUnknownFields() {
        String json = """
        {
          "type": "ProductionStatusChanged",
          "eventId": "11111111-1111-1111-1111-111111111111",
          "unknownRootField": "foo",
          "payload": {
            "productionId": 200,
            "previousStatus": "CONFIRMADO",
            "newStatus": "EN_MONTAJE",
            "occurredAt": "2026-10-11T13:00:00Z",
            "unknownPayloadField": 999
          }
        }
        """;

        Optional<ProductionStatusChangedEvent> result = mapper.mapAndValidate(json);
        assertThat(result).isPresent();
        assertThat(result.get().productionId()).isEqualTo(200L);
    }

    @Test
    void testMapAndValidateIgnoresDifferentType() {
        String json = """
        {
          "type": "ProductionCreated",
          "eventId": "11111111-1111-1111-1111-111111111111",
          "payload": {
            "productionId": 300
          }
        }
        """;

        Optional<ProductionStatusChangedEvent> result = mapper.mapAndValidate(json);
        assertThat(result).isEmpty();
    }

    @Test
    void testMapAndValidateRejectsInvalidStatus() {
        String json = """
        {
          "type": "ProductionStatusChanged",
          "eventId": "11111111-1111-1111-1111-111111111111",
          "payload": {
            "productionId": 100,
            "previousStatus": "SOLICITADO",
            "newStatus": "ESTADO_INEXISTENTE",
            "occurredAt": "2026-10-11T12:00:00Z"
          }
        }
        """;

        assertThatThrownBy(() -> mapper.mapAndValidate(json))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("Estado inválido");
    }

    @Test
    void testMapAndValidateRejectsMissingMandatoryField() {
        String json = """
        {
          "type": "ProductionStatusChanged",
          "eventId": "11111111-1111-1111-1111-111111111111",
          "payload": {
            "previousStatus": "SOLICITADO",
            "newStatus": "CONFIRMADO",
            "occurredAt": "2026-10-11T12:00:00Z"
          }
        }
        """;

        assertThatThrownBy(() -> mapper.mapAndValidate(json))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("productionId");
    }

    @Test
    void testMapAndValidateRejectsMalformedJson() {
        String malformedJson = "{ invalid-json }";

        assertThatThrownBy(() -> mapper.mapAndValidate(malformedJson))
                .isInstanceOf(NonRetryableEventException.class);
    }
}

