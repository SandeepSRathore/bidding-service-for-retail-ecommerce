package com.retail.bidding.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published when a bidding window closes and the lowest price-per-order bid wins every order in it.
 * {@code participantVendorIds} contains every vendor that bid, winner included.
 */
public record AuctionAwarded(
        UUID eventId,
        UUID auctionId,
        List<UUID> orderIds,
        UUID winnerVendorId,
        BigDecimal pricePerOrder,
        BigDecimal totalCharge,
        List<UUID> participantVendorIds,
        Instant awardedAt) {
}
