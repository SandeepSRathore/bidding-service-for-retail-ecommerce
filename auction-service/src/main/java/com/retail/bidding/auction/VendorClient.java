package com.retail.bidding.auction;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Component
class VendorClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VendorView(UUID id, String status) {
    }

    private final RestClient http;

    VendorClient(RestClient.Builder builder, AuctionProperties properties) {
        this.http = builder.baseUrl(properties.vendorServiceUrl()).build();
    }

    /** Rejects bids from vendors that are unknown or have been deactivated since their token was issued. */
    @Retryable(includes = {ResourceAccessException.class, HttpServerErrorException.class}, maxRetries = 2)
    void requireActive(UUID vendorId) {
        VendorView vendor;
        try {
            vendor = http.get().uri("/vendors/{id}", vendorId).retrieve().body(VendorView.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "VENDOR_NOT_ACTIVE", "Unknown vendor " + vendorId);
        }
        if (vendor == null || !"ACTIVE".equals(vendor.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "VENDOR_NOT_ACTIVE", "Vendor " + vendorId + " is not active");
        }
    }
}
