package com.retail.bidding.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.sun.net.httpserver.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.dev-token.enabled=true")
@AutoConfigureWebTestClient
class GatewaySecurityTest {

    /** Stands in for every downstream service: replies with the identity headers it received. */
    static final HttpServer backend = startEchoBackend();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + backend.getAddress().getPort();
        registry.add("VENDOR_SERVICE_URL", () -> url);
        registry.add("ORDER_SERVICE_URL", () -> url);
        registry.add("AUCTION_SERVICE_URL", () -> url);
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @Autowired
    WebTestClient client;

    @Test
    void rejectsRequestsWithoutAToken() {
        client.get().uri("/orders").exchange().expectStatus().isUnauthorized();
        client.get().uri("/auctions").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void vendorCanBidAndIdentityComesFromTheTokenNotTheClient() {
        UUID vendorId = UUID.randomUUID();
        String token = token("{\"role\":\"VENDOR\",\"vendorId\":\"" + vendorId + "\"}");

        client.put().uri("/auctions/{id}/bid", UUID.randomUUID())
                .headers(h -> h.setBearerAuth(token))
                .header(IdentityHeadersFilter.USER_ROLE, "ADMIN") // spoof attempt
                .header(IdentityHeadersFilter.USER_ID, "someone-else")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"pricePerOrder\":100}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(vendorId + "|VENDOR");
    }

    @Test
    void vendorCannotUseAdminEndpoints() {
        String token = token("{\"role\":\"VENDOR\",\"vendorId\":\"" + UUID.randomUUID() + "\"}");

        client.post().uri("/auctions").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isForbidden();
        client.get().uri("/vendors").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/auctions/{id}", UUID.randomUUID()).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk();
    }

    @Test
    void adminCanManageButCannotBid() {
        String token = token("{\"role\":\"ADMIN\"}");

        client.get().uri("/vendors").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("admin|ADMIN");
        client.put().uri("/auctions/{id}/bid", UUID.randomUUID()).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"pricePerOrder\":100}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void internalEndpointsAreNotExposed() {
        String token = token("{\"role\":\"ADMIN\"}");

        client.post().uri("/internal/orders/lock").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void rejectsForgedAndMalformedTokens() {
        String forged = NimbusJwtEncoder.withSecretKey(
                        new JwtProperties("an-attacker-controlled-key-0123456789abcdef", null).key()).build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder().subject("admin").claim("role", "ADMIN")
                                .expiresAt(Instant.now().plusSeconds(60)).build()))
                .getTokenValue();
        client.get().uri("/orders").headers(h -> h.setBearerAuth(forged))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/orders").headers(h -> h.setBearerAuth("not-a-jwt"))
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/auth/dev-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"role\":\"VENDOR\"}")
                .exchange().expectStatus().isBadRequest();
    }

    private String token(String request) {
        return client.post().uri("/auth/dev-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DevTokenController.TokenResponse.class)
                .returnResult().getResponseBody().accessToken();
    }

    private static HttpServer startEchoBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                var headers = exchange.getRequestHeaders();
                byte[] body = (headers.getFirst(IdentityHeadersFilter.USER_ID) + "|"
                        + headers.getFirst(IdentityHeadersFilter.USER_ROLE)).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
