package com.retail.bidding.notification;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Component
class OrderClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderSummary(UUID id, String customerRef, String pickupAddress, String dropAddress,
                        BigDecimal weightKg) {
    }

    private final RestClient http;

    OrderClient(RestClient.Builder builder, NotificationProperties properties) {
        this.http = builder.baseUrl(properties.orderServiceUrl()).build();
    }

    @Retryable(includes = {ResourceAccessException.class, HttpServerErrorException.class}, maxRetries = 2)
    List<OrderSummary> ordersOf(UUID auctionId) {
        return http.get()
                .uri("/orders?auctionId={id}", auctionId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }
}
