package com.retail.bidding.auction;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param windowDuration how long a bidding window stays open (1 hour in production)
 * @param orderServiceUrl base URL of order-service
 * @param vendorServiceUrl base URL of vendor-service
 */
@ConfigurationProperties("auction")
public record AuctionProperties(
        @DefaultValue("PT1H") Duration windowDuration,
        @DefaultValue("http://localhost:8082") String orderServiceUrl,
        @DefaultValue("http://localhost:8081") String vendorServiceUrl) {
}
