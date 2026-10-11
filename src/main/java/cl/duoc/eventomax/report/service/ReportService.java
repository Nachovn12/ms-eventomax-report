package cl.duoc.eventomax.report.service;

import cl.duoc.eventomax.report.dto.KpisReportResponse;
import cl.duoc.eventomax.report.model.enums.ReportRange;

public interface ReportService {
    KpisReportResponse calculateKpis(ReportRange range);
}

