package com.retail.bidding.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Order(
        UUID id,
        String customerRef,
        String pickupAddress,
        String dropAddress,
        BigDecimal weightKg,
        OrderStatus status,
        UUID auctionId,
        UUID vendorId,
        BigDecimal deliveryCharge,
        Instant createdAt,
        Instant updatedAt) {
}
