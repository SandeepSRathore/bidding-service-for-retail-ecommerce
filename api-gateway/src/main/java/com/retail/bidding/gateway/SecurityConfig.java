package com.retail.bidding.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
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

    @Bean
    ReactiveJwtDecoder jwtDecoder(JwtProperties properties) {
        return NimbusReactiveJwtDecoder.withSecretKey(properties.key()).macAlgorithm(MacAlgorithm.HS256).build();
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
