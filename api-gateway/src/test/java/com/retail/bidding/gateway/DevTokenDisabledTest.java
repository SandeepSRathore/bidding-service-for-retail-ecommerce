package com.retail.bidding.gateway;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The default configuration: no dev token endpoint, and dev-issued tokens are refused. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class DevTokenDisabledTest {

    @Autowired
    WebTestClient client;

    @Autowired
    JwtProperties jwt;

    @Test
    void devTokenEndpointIsOffByDefault() {
        client.post().uri("/auth/dev-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"role\":\"ADMIN\"}")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void validlySignedDevTokensAreRejectedWhileTheEndpointIsOff() {
        String token = NimbusJwtEncoder.withSecretKey(jwt.key()).build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder().issuer(DevTokenController.ISSUER).subject("admin")
                                .claim("role", "ADMIN").expiresAt(Instant.now().plusSeconds(60)).build()))
                .getTokenValue();

        client.get().uri("/vendors").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isUnauthorized();
    }
}
