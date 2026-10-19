package cl.duoc.eventomax.report.dto;

import java.time.Instant;

public record KpisReportResponse(
        String range,
        Instant from,
        Instant to,
        Instant generatedAt,
        PartialCoverageDto partialCoverage,
        KpiMetricsDto metrics
) {}

