package com.retail.bidding.gateway;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues signed test tokens so the system can be exercised locally without an identity provider.
 * Disable with {@code gateway.dev-token.enabled=false} anywhere real users exist.
 */
@RestController
@ConditionalOnProperty(name = "gateway.dev-token.enabled", havingValue = "true")
class DevTokenController {

    enum Role { ADMIN, VENDOR }

    /** {@code vendorId} is required for vendor tokens: it becomes the token subject. */
    record TokenRequest(@NotNull Role role, UUID vendorId) {
    }

    record TokenResponse(String accessToken, Instant expiresAt) {
    }

    private final NimbusJwtEncoder encoder;
    private final JwtProperties properties;

    DevTokenController(JwtProperties properties) {
        this.encoder = NimbusJwtEncoder.withSecretKey(properties.key()).build();
        this.properties = properties;
    }

    @PostMapping("/auth/dev-token")
    TokenResponse issue(@Valid @RequestBody TokenRequest request) {
        String subject = switch (request.role()) {
            case ADMIN -> "admin";
            case VENDOR -> {
                if (request.vendorId() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "vendorId is required for VENDOR tokens");
                }
                yield request.vendorId().toString();
            }
        };
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("bidding-gateway-dev")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(SecurityConfig.ROLE_CLAIM, request.role().name())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new TokenResponse(token, expiresAt);
    }
}
