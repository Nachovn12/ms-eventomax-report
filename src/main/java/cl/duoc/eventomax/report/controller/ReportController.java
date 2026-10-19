package cl.duoc.eventomax.report.controller;

import cl.duoc.eventomax.report.dto.KpisReportResponse;
import cl.duoc.eventomax.report.dto.TopServicesReportResponse;
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

    @GetMapping(value = "/top-services", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Obtener top servicios contratados", description = "Calcula el ranking de servicios más contratados en la ventana temporal especificada.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ranking de servicios calculado exitosamente",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = TopServicesReportResponse.class))),
            @ApiResponse(responseCode = "400", description = "Rango temporal inválido",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TopServicesReportResponse> getTopServices(
            @RequestParam(name = "range", required = false, defaultValue = "last7d") String rangeParam) {
        ReportRange range = ReportRange.fromString(rangeParam);
        TopServicesReportResponse response = reportService.calculateTopServices(range);
        return ResponseEntity.ok(response);
    }
}

