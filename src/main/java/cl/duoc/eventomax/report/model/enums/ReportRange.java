package cl.duoc.eventomax.report.model.enums;

import cl.duoc.eventomax.report.exception.InvalidReportRangeException;

import java.time.Duration;

public enum ReportRange {
    LAST_24H("last24h", Duration.ofHours(24)),
    LAST_7D("last7d", Duration.ofDays(7));

    private final String value;
    private final Duration duration;

    ReportRange(String value, Duration duration) {
        this.value = value;
        this.duration = duration;
    }

    public String getValue() {
        return value;
    }

    public Duration getDuration() {
        return duration;
    }

    public static ReportRange fromString(String text) {
        if (text == null || text.isBlank()) {
            return LAST_24H;
        }
        for (ReportRange r : values()) {
            if (r.value.equalsIgnoreCase(text.trim())) {
                return r;
            }
        }
        throw new InvalidReportRangeException(text);
    }
}

