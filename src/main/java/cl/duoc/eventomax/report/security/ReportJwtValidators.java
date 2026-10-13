package cl.duoc.eventomax.report.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

import java.util.Objects;

public final class ReportJwtValidators {

    private ReportJwtValidators() {}

    /**
     * Construye un validador combinado de tokens JWT que verifica:
     * 1. Emisor esperado (issuer-uri) y marcas temporales (timestamp: nbf / exp) vía JwtValidators.createDefaultWithIssuer.
     * 2. Audiencia esperada (audience) vía JwtAudienceValidator.
     *
     * Este método centralizado es utilizado tanto por SupplierJwtDecoder en tiempo de ejecución
     * como por la suite de pruebas unitarias.
     *
     * @param issuerUri URI del emisor de Microsoft Entra ID
     * @param audience Identificador de audiencia esperado
     * @return Validador combinado DelegatingOAuth2TokenValidator
     */
    public static OAuth2TokenValidator<Jwt> create(String issuerUri, String audience) {
        Objects.requireNonNull(issuerUri, "issuerUri no puede ser nulo");
        Objects.requireNonNull(audience, "audience no puede ser nulo");

        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtAudienceValidator(audience);
        return new DelegatingOAuth2TokenValidator<>(withIssuer, audienceValidator);
    }
}

