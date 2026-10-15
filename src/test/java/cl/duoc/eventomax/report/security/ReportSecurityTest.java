package cl.duoc.eventomax.report.security;

import cl.duoc.eventomax.report.dto.KpiMetricsDto;
import cl.duoc.eventomax.report.dto.KpisReportResponse;
import cl.duoc.eventomax.report.dto.PartialCoverageDto;
import cl.duoc.eventomax.report.dto.TransitionCountDto;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import cl.duoc.eventomax.report.model.enums.ReportRange;
import cl.duoc.eventomax.report.service.ReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ReportSecurityTest {

    private static final String ISSUER = "https://login.microsoftonline.com/f191bdc8-7926-4722-9ec0-cba663701940/v2.0";
    private static final String AUDIENCE = "a1a87bcd-fed7-4d65-83c3-338389b80093";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportService reportService;

    private KpisReportResponse buildMockResponse() {
        Instant now = Instant.parse("2026-10-11T12:00:00Z");
        Instant from = now.minusSeconds(86400);
        Map<ProductionStatus, Long> distribution = new LinkedHashMap<>();
        for (ProductionStatus status : ProductionStatus.values()) {
            distribution.put(status, 0L);
        }
        distribution.put(ProductionStatus.CONFIRMADO, 1L);

        KpiMetricsDto metrics = new KpiMetricsDto(
                1L,
                distribution,
                List.of(new TransitionCountDto(ProductionStatus.SOLICITADO, ProductionStatus.CONFIRMADO, 1L)),
                0L,
                0L
        );
        PartialCoverageDto partialCoverage = new PartialCoverageDto(
                true,
                "Cobertura parcial de transiciones de prueba"
        );
        return new KpisReportResponse("last24h", from, now, now, partialCoverage, metrics);
    }

    @Test
    @DisplayName("Petición sin JWT a GET /api/report/kpis debe responder 401 ProblemDetail en español")
    void testGetKpisWithoutTokenReturns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/report/kpis"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", is("about:blank")))
                .andExpect(jsonPath("$.title", is("No autenticado")))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.detail", is("Se requiere un token JWT válido para acceder a este recurso.")))
                .andExpect(jsonPath("$.instance", is("/api/report/kpis")));
    }

    @Test
    @DisplayName("Petición sin JWT a GET /api/report/top-services debe responder 401 ProblemDetail")
    void testGetTopServicesWithoutTokenReturns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/report/top-services"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.title", is("No autenticado")));
    }

    @Test
    @DisplayName("Test unitario de validador combinado: debe rechazar audiencia incorrecta y aceptar audiencia válida")
    void testCombinedValidatorAudienceValidation() {
        OAuth2TokenValidator<Jwt> validator = ReportJwtValidators.create(ISSUER, AUDIENCE);

        // JWT con audiencia incorrecta
        Jwt wrongAudienceJwt = Jwt.withTokenValue("mock-token-wrong-aud")
                .header("alg", "none")
                .issuer(ISSUER)
                .audience(List.of("audiencia-invalida-12345"))
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        OAuth2TokenValidatorResult failureResult = validator.validate(wrongAudienceJwt);
        assertThat(failureResult.hasErrors()).isTrue();
        assertThat(failureResult.getErrors()).anyMatch(e -> "invalid_token".equals(e.getErrorCode()));

        // JWT con audiencia válida y emisor correcto
        Jwt validJwt = Jwt.withTokenValue("mock-token-valid-aud")
                .header("alg", "none")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        OAuth2TokenValidatorResult successResult = validator.validate(validJwt);
        assertThat(successResult.hasErrors()).isFalse();
    }

    @Test
    @DisplayName("Token válido con rol no Admin (ej. Productor/Organizador) debe responder 403 ProblemDetail en español")
    void testGetKpisWithNonAdminRoleReturns403ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/report/kpis")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_Organizador"),
                                new SimpleGrantedAuthority("SCOPE_access_as_user")
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type", is("about:blank")))
                .andExpect(jsonPath("$.title", is("Acceso denegado")))
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.detail", is("No posee los permisos suficientes para acceder a este recurso. Se requiere rol Admin y scope access_as_user.")))
                .andExpect(jsonPath("$.instance", is("/api/report/kpis")));
    }

    @Test
    @DisplayName("Token válido con rol Admin pero sin scope access_as_user debe responder 403 ProblemDetail")
    void testGetKpisWithAdminRoleWithoutScopeReturns403ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/report/kpis")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_Admin")
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.title", is("Acceso denegado")));
    }

    @Test
    @DisplayName("Token válido con ROLE_Admin Y SCOPE_access_as_user a GET /api/report/kpis debe responder 200 OK")
    void testGetKpisWithAdminAndScopeReturns200() throws Exception {
        when(reportService.calculateKpis(any(ReportRange.class))).thenReturn(buildMockResponse());

        mockMvc.perform(get("/api/report/kpis")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_Admin"),
                                new SimpleGrantedAuthority("SCOPE_access_as_user")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range", is("last24h")))
                .andExpect(jsonPath("$.metrics.totalStatusChanges", is(1)));
    }

    @Test
    @DisplayName("Token válido con ROLE_Admin Y SCOPE_access_as_user a GET /api/report/top-services debe responder 501 Not Implemented")
    void testGetTopServicesWithAdminAndScopeReturns501ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/report/top-services")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_Admin"),
                                new SimpleGrantedAuthority("SCOPE_access_as_user")
                        )))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.status", is(501)))
                .andExpect(jsonPath("$.title", is("Funcionalidad no implementada")))
                .andExpect(jsonPath("$.instance", is("/api/report/top-services")));
    }

    @Test
    @DisplayName("GET /actuator/health debe responder 200 OK sin requerir token (healthcheck de Docker)")
    void testActuatorHealthWithoutTokenReturns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    @DisplayName("Métodos de escritura (POST, PUT, PATCH, DELETE) sin token deben responder 401 Unauthorized")
    void testWriteMethodsWithoutTokenReturn401() throws Exception {
        mockMvc.perform(post("/api/report/kpis").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));

        mockMvc.perform(put("/api/report/kpis").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));

        mockMvc.perform(patch("/api/report/kpis").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));

        mockMvc.perform(delete("/api/report/kpis"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    @DisplayName("Métodos de escritura (POST, PUT, PATCH, DELETE) con Admin+scope deben responder 403 Forbidden por denyAll")
    void testWriteMethodsWithAdminAndScopeReturn403Forbidden() throws Exception {
        var adminAuth = jwt().authorities(
                new SimpleGrantedAuthority("ROLE_Admin"),
                new SimpleGrantedAuthority("SCOPE_access_as_user")
        );

        // POST /api/report/kpis
        mockMvc.perform(post("/api/report/kpis")
                        .with(adminAuth)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.title", is("Acceso denegado")));

        // PUT /api/report/kpis
        mockMvc.perform(put("/api/report/kpis")
                        .with(adminAuth)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)));

        // PATCH /api/report/kpis
        mockMvc.perform(patch("/api/report/kpis")
                        .with(adminAuth)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)));

        // DELETE /api/report/kpis
        mockMvc.perform(delete("/api/report/kpis")
                        .with(adminAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)));
    }

    @Test
    @DisplayName("Rutas no expuestas (swagger/openapi, otros actuator) sin token deben responder 401")
    void testOtherEndpointsAreDenied() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
    }
}

