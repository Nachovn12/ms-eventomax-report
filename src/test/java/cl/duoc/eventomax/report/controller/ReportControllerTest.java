package cl.duoc.eventomax.report.controller;

import cl.duoc.eventomax.report.controller.advice.ReportExceptionHandler;
import cl.duoc.eventomax.report.dto.KpiMetricsDto;
import cl.duoc.eventomax.report.dto.KpisReportResponse;
import cl.duoc.eventomax.report.dto.PartialCoverageDto;
import cl.duoc.eventomax.report.dto.TransitionCountDto;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import cl.duoc.eventomax.report.model.enums.ReportRange;
import cl.duoc.eventomax.report.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@WebMvcTest(
        controllers = ReportController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class,
                OAuth2ResourceServerWebSecurityAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(ReportExceptionHandler.class)
public class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportService reportService;

    private KpisReportResponse buildMockResponse(String rangeStr) {
        Instant now = Instant.parse("2026-10-11T12:00:00Z");
        Instant from = now.minusSeconds(86400);
        Map<ProductionStatus, Long> distribution = new LinkedHashMap<>();
        for (ProductionStatus status : ProductionStatus.values()) {
            distribution.put(status, 0L);
        }
        distribution.put(ProductionStatus.CONFIRMADO, 2L);

        KpiMetricsDto metrics = new KpiMetricsDto(
                2L,
                distribution,
                List.of(new TransitionCountDto(ProductionStatus.SOLICITADO, ProductionStatus.CONFIRMADO, 2L)),
                0L,
                0L
        );
        PartialCoverageDto partialCoverage = new PartialCoverageDto(
                true,
                "Métricas calculadas exclusivamente sobre eventos de transición de estado (ProductionStatusChanged). No representa el inventario total de producciones del sistema ni incluye producciones sin cambios de estado en el rango."
        );
        return new KpisReportResponse(rangeStr, from, now, now, partialCoverage, metrics);
    }

    @Test
    void testGetKpisDefaultRangeReturns200() throws Exception {
        when(reportService.calculateKpis(eq(ReportRange.LAST_24H)))
                .thenReturn(buildMockResponse("last24h"));

        mockMvc.perform(get("/api/report/kpis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range", is("last24h")))
                .andExpect(jsonPath("$.partialCoverage.isPartial", is(true)))
                .andExpect(jsonPath("$.metrics.totalStatusChanges", is(2)))
                .andExpect(jsonPath("$.metrics.currentStatusDistribution.CONFIRMADO", is(2)))
                .andExpect(jsonPath("$.metrics.currentStatusDistribution.SOLICITADO", is(0)));
    }

    @Test
    void testGetKpisWithRangeLast7dReturns200() throws Exception {
        when(reportService.calculateKpis(eq(ReportRange.LAST_7D)))
                .thenReturn(buildMockResponse("last7d"));

        mockMvc.perform(get("/api/report/kpis").param("range", "last7d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range", is("last7d")));
    }

    @Test
    void testGetKpisInvalidRangeReturns400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/report/kpis").param("range", "invalidRange"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.title", is("Solicitud inválida")))
                .andExpect(jsonPath("$.detail", is("El valor del parámetro 'range' no es válido.")))
                .andExpect(jsonPath("$.instance", is("/api/report/kpis")))
                .andExpect(jsonPath("$.permittedValues", contains("last24h", "last7d")));
    }

    @Test
    void testPostKpisReturns405MethodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/report/kpis"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void testGetTopServicesDefaultRangeReturns501ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/report/top-services"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.status", is(501)))
                .andExpect(jsonPath("$.title", is("Funcionalidad no implementada")))
                .andExpect(jsonPath("$.detail", is("El reporte de top-services está diferido: requiere serviceId y datos reales de contratación de servicios no disponibles en el contrato actual.")))
                .andExpect(jsonPath("$.instance", is("/api/report/top-services")));
    }

    @Test
    void testGetTopServicesWithRangeLast7dReturns501() throws Exception {
        mockMvc.perform(get("/api/report/top-services").param("range", "last7d"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.status", is(501)));
    }

    @Test
    void testGetTopServicesInvalidRangeReturns400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/report/top-services").param("range", "last30d"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.title", is("Solicitud inválida")))
                .andExpect(jsonPath("$.instance", is("/api/report/top-services")))
                .andExpect(jsonPath("$.permittedValues", contains("last24h", "last7d")));
    }

    @Test
    void testPostTopServicesReturns405MethodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/report/top-services"))
                .andExpect(status().isMethodNotAllowed());
    }
}

