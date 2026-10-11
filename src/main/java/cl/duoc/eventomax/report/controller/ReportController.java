package cl.duoc.eventomax.report.controller;

import cl.duoc.eventomax.report.dto.KpisReportResponse;
import cl.duoc.eventomax.report.exception.TopServicesNotImplementedException;
import cl.duoc.eventomax.report.model.enums.ReportRange;
import cl.duoc.eventomax.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/report")
@Tag(name = "Reportes", description = "Endpoints de solo lectura para reportería y KPIs")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping(value = "/kpis", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Obtener KPIs de cambios de estado", description = "Calcula indicadores agregados sobre eventos de transiciones de producciones en la ventana temporal especificada.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "KPIs calculados exitosamente",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = KpisReportResponse.class))),
            @ApiResponse(responseCode = "400", description = "Rango temporal inválido",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<KpisReportResponse> getKpis(
            @RequestParam(name = "range", required = false, defaultValue = "last24h") String rangeParam) {
        ReportRange range = ReportRange.fromString(rangeParam);
        KpisReportResponse response = reportService.calculateKpis(range);
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/top-services", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    @Operation(summary = "Obtener top servicios contratados (Diferido)", description = "Endpoint diferido por falta de serviceId y datos contractuales de contratación de servicios en el contrato actual.")
    @ApiResponses({
            @ApiResponse(responseCode = "501", description = "Funcionalidad no implementada",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "400", description = "Rango temporal inválido",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> getTopServices(
            @RequestParam(name = "range", required = false, defaultValue = "last7d") String rangeParam) {
        ReportRange.fromString(rangeParam);
        throw new TopServicesNotImplementedException();
    }
}

