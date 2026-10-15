package cl.duoc.eventomax.report.consumer;

import cl.duoc.eventomax.report.consumer.mapper.ProductionEventMapper;
import cl.duoc.eventomax.report.consumer.model.ProductionStatusChangedEvent;
import cl.duoc.eventomax.report.consumer.service.ProductionEventIngestionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ProductionEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionEventListener.class);

    private final ProductionEventMapper eventMapper;
    private final ProductionEventIngestionService ingestionService;

    public ProductionEventListener(ProductionEventMapper eventMapper,
                                   ProductionEventIngestionService ingestionService) {
        this.eventMapper = eventMapper;
        this.ingestionService = ingestionService;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.production-events:productions.events}",
            groupId = "${spring.kafka.consumer.group-id:report-group}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        String payload = record.value();

        Optional<ProductionStatusChangedEvent> eventOpt = eventMapper.mapAndValidate(payload);
        if (eventOpt.isEmpty()) {
            return;
        }

        ProductionStatusChangedEvent event = eventOpt.get();

        try {
            if (event.traceId() != null) {
                MDC.put("traceId", event.traceId());
            }
            if (event.correlationId() != null) {
                MDC.put("correlationId", event.correlationId());
            }
            if (event.eventId() != null) {
                MDC.put("eventId", event.eventId().toString());
            }

            log.info("Procesando evento de producción. eventId={}, productionId={}, previousStatus={}, newStatus={}",
                    event.eventId(), event.productionId(), event.previousStatus(), event.newStatus());

            ingestionService.ingest(event);
        } finally {
            MDC.remove("traceId");
            MDC.remove("correlationId");
            MDC.remove("eventId");
        }
    }
}

