package cl.duoc.eventomax.report.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContractedServiceEventDto(
        Long serviceId,
        String serviceName,
        BigDecimal unitPrice
) {}
