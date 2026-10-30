package com.retail.bidding.auction;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatusCode;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls order-service's internal API. Both calls are idempotent per auctionId, so they are retried. */
@Component
class OrderClient {

    private record LockRequest(UUID auctionId, List<UUID> orderIds) {
    }

    private record ReleaseRequest(UUID auctionId) {
    }

    private final RestClient http;
    private final JsonMapper json;

    OrderClient(RestClient.Builder builder, AuctionProperties properties, JsonMapper json) {
        this.http = builder.baseUrl(properties.orderServiceUrl()).build();
        this.json = json;
    }

    /** Locks the orders to this window; 404 or 409 from order-service is passed through to the caller. */
    @Retryable(includes = {ResourceAccessException.class, HttpServerErrorException.class}, maxRetries = 2)
    void lockOrders(UUID auctionId, List<UUID> orderIds) {
        http.post()
                .uri("/internal/orders/lock")
                .body(new LockRequest(auctionId, orderIds))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new ApiException(response.getStatusCode(), "ORDERS_UNAVAILABLE",
                            detailOf(new String(response.getBody().readAllBytes())));
                })
                .toBodilessEntity();
    }

    @Retryable(includes = {ResourceAccessException.class, HttpServerErrorException.class}, maxRetries = 2)
    void releaseOrders(UUID auctionId) {
        http.post()
                .uri("/internal/orders/release")
                .body(new ReleaseRequest(auctionId))
                .retrieve()
                .toBodilessEntity();
    }

    private String detailOf(String problemJson) {
        try {
            JsonNode detail = json.readTree(problemJson).get("detail");
            return detail != null ? detail.asString() : problemJson;
        } catch (RuntimeException e) {
            return problemJson;
        }
    }
}
