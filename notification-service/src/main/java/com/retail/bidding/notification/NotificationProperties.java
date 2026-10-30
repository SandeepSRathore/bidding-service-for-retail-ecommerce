package com.retail.bidding.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param fromAddress sender address on every email
 * @param biddingPortalUrl public URL vendors use to bid (the API gateway)
 */
@ConfigurationProperties("notification")
public record NotificationProperties(
        @DefaultValue("bidding@retailer.example") String fromAddress,
        @DefaultValue("http://localhost:8080") String biddingPortalUrl,
        @DefaultValue("http://localhost:8081") String vendorServiceUrl,
        @DefaultValue("http://localhost:8082") String orderServiceUrl) {
}
