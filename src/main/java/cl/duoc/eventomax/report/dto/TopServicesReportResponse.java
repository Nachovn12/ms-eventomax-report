package cl.duoc.eventomax.report.dto;

import java.time.Instant;
import java.util.List;

public record TopServicesReportResponse(
        String range,
        Instant from,
        Instant to,
        Instant generatedAt,
        PartialCoverageDto partialCoverage,
        List<TopServiceItemDto> items
) {}
