package com.retail.bidding.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Role rules for every public route. Anything not listed (including order-service's /internal/**
 * endpoints) is denied, so internal APIs can't be reached through the gateway.
 */
@Configuration
class SecurityConfig {

    static final String ROLE_CLAIM = "role";

    @Bean
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/actuator/health/**", "/auth/dev-token").permitAll()
                        .pathMatchers("/auctions/*/bid").hasRole("VENDOR")
                        .pathMatchers(HttpMethod.GET, "/auctions", "/auctions/*").hasAnyRole("ADMIN", "VENDOR")
                        .pathMatchers(HttpMethod.POST, "/auctions").hasRole("ADMIN")
                        .pathMatchers("/vendors/**", "/orders/**").hasRole("ADMIN")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter())))
                .build();
    }

    /**
     * When the dev token endpoint is off, tokens it issued earlier are rejected too, so a dev token
     * can never be replayed against an environment that shares the signing key.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(JwtProperties properties,
                                  @Value("${gateway.dev-token.enabled:false}") boolean devTokensEnabled) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(properties.key())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        if (!devTokensEnabled) {
            decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(rejectDevIssuer()));
        }
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> rejectDevIssuer() {
        return jwt -> DevTokenController.ISSUER.equals(jwt.getClaimAsString("iss"))
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Dev tokens are disabled", null))
                : OAuth2TokenValidatorResult.success();
    }

    private static ReactiveJwtAuthenticationConverterAdapter authenticationConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
