package com.retail.bidding.notification;

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
class VendorClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VendorContact(UUID id, String name, String email) {
    }

    private final RestClient http;

    VendorClient(RestClient.Builder builder, NotificationProperties properties) {
        this.http = builder.baseUrl(properties.vendorServiceUrl()).build();
    }

    @Retryable(includes = {ResourceAccessException.class, HttpServerErrorException.class}, maxRetries = 2)
    List<VendorContact> activeVendors() {
        return http.get()
                .uri("/vendors?status=ACTIVE")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    @Retryable(includes = {ResourceAccessException.class, HttpServerErrorException.class}, maxRetries = 2)
    VendorContact get(UUID vendorId) {
        return http.get().uri("/vendors/{id}", vendorId).retrieve().body(VendorContact.class);
    }
}
