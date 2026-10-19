package cl.duoc.eventomax.report.config;

import cl.duoc.eventomax.report.security.ProblemDetailAccessDeniedHandler;
import cl.duoc.eventomax.report.security.ProblemDetailAuthenticationEntryPoint;
import cl.duoc.eventomax.report.security.ReportJwtValidators;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.ArrayList;
import java.util.Collection;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemDetailAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
                          ProblemDetailAccessDeniedHandler accessDeniedHandler) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

        AuthorizationManager<RequestAuthorizationContext> requireAdminAndScope = (authentication, context) -> {
            var auth = authentication.get();
            if (auth == null || !auth.isAuthenticated()) {
                return new AuthorizationDecision(false);
            }
            boolean hasAdminRole = auth.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_Admin".equals(a.getAuthority()));
            boolean hasAccessScope = auth.getAuthorities().stream()
                    .anyMatch(a -> "SCOPE_access_as_user".equals(a.getAuthority()));
            return new AuthorizationDecision(hasAdminRole && hasAccessScope);
        };

        http
                // Stateless REST API: CSRF deshabilitado porque la autenticación es vía Bearer token sin cookies ni sesión web
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // Healthcheck de Docker: GET /actuator/health y subrutas permitidas sin autenticación
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // Endpoints protegidos de solo lectura: exigen ROLE_Admin Y SCOPE_access_as_user
                        .requestMatchers(HttpMethod.GET, "/api/report/kpis").access(requireAdminAndScope)
                        .requestMatchers(HttpMethod.GET, "/api/report/top-services").access(requireAdminAndScope)
                        // Cualquier otra ruta o método (incluyendo escritura POST/PUT/PATCH/DELETE y otros actuator/swagger) queda denegado
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
                        )
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${app.security.audience:${eventomax.security.jwt.audience:${ENTRA_AUDIENCE:}}}") String audience) {
        // SupplierJwtDecoder posterga el descubrimiento OIDC de Entra hasta el primer token recibido.
        // Esto evita dependencias de red en el arranque de la aplicación y en el healthcheck de Docker.
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
            decoder.setJwtValidator(ReportJwtValidators.create(issuerUri, audience));
            return decoder;
        });
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();

            // 1. Mapeo de claim roles -> ROLE_<rol>
            Object rolesClaim = jwt.getClaims().get("roles");
            if (rolesClaim instanceof Collection<?> roles) {
                for (Object role : roles) {
                    if (role != null && !role.toString().isBlank()) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toString().trim()));
                    }
                }
            } else if (rolesClaim instanceof String roleStr && !roleStr.isBlank()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + roleStr.trim()));
            }

            // 2. Mapeo de claim scp / scope -> SCOPE_<scope>
            Object scpClaim = jwt.getClaims().get("scp");
            if (scpClaim == null) {
                scpClaim = jwt.getClaims().get("scope");
            }
            if (scpClaim instanceof String scpStr) {
                for (String scope : scpStr.split("\\s+")) {
                    if (!scope.isBlank()) {
                        authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope.trim()));
                    }
                }
            } else if (scpClaim instanceof Collection<?> scpList) {
                for (Object scope : scpList) {
                    if (scope != null && !scope.toString().isBlank()) {
                        authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope.toString().trim()));
                    }
                }
            }

            return authorities;
        });
        return converter;
    }
}

