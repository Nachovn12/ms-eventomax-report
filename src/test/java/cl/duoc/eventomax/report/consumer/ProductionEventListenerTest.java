package cl.duoc.eventomax.report.consumer;

import cl.duoc.eventomax.report.consumer.mapper.ProductionEventMapper;
import cl.duoc.eventomax.report.consumer.model.ProductionStatusChangedEvent;
import cl.duoc.eventomax.report.consumer.service.ProductionEventIngestionService;
import cl.duoc.eventomax.report.exception.NonRetryableEventException;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ProductionEventListenerTest {

    @Mock
    private ProductionEventMapper mapper;

    @Mock
    private ProductionEventIngestionService ingestionService;

    private ProductionEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new ProductionEventListener(mapper, ingestionService);
    }

    @Test
    void testOnMessageProcessesValidEventAndCleansMdc() {
        String json = "{\"type\":\"ProductionStatusChanged\"}";
        UUID eventId = UUID.randomUUID();
        ProductionStatusChangedEvent event = new ProductionStatusChangedEvent(
                eventId,
                "ProductionStatusChanged",
                100L,
                ProductionStatus.SOLICITADO,
                ProductionStatus.CONFIRMADO,
                Instant.now(),
                "trace-123",
                "corr-456"
        );

        when(mapper.mapAndValidate(json)).thenReturn(Optional.of(event));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("productions.events", 0, 0L, "key", json);
        listener.onMessage(record);

        verify(ingestionService).ingest(event);

        // Verifica que MDC quedó limpio en el bloque finally
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("eventId")).isNull();
    }

    @Test
    void testOnMessageIgnoresOtherTypeAndDoesNotCallIngestion() {
        String json = "{\"type\":\"ProductionCreated\"}";

        when(mapper.mapAndValidate(json)).thenReturn(Optional.empty());

        ConsumerRecord<String, String> record = new ConsumerRecord<>("productions.events", 0, 0L, "key", json);
        listener.onMessage(record);

        verify(ingestionService, never()).ingest(any());
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void testOnMessagePropagatesExceptionAndCleansMdc() {
        String invalidJson = "{\"invalid\":true}";
        when(mapper.mapAndValidate(invalidJson)).thenThrow(new NonRetryableEventException("Campo faltante"));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("productions.events", 0, 0L, "key", invalidJson);

        assertThatThrownBy(() -> listener.onMessage(record))
                .isInstanceOf(NonRetryableEventException.class);

        assertThat(MDC.get("traceId")).isNull();
    }
}

