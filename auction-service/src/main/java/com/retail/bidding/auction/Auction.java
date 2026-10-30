package com.retail.bidding.auction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Auction(
        UUID id,
        AuctionStatus status,
        Instant startsAt,
        Instant endsAt,
        UUID winnerVendorId,
        BigDecimal winningPricePerOrder,
        Instant closedAt,
        String createdBy,
        Instant createdAt) {

    /** Bids are accepted only while the window is open by the clock, regardless of when the closer runs. */
    boolean acceptsBidsAt(Instant now) {
        return status == AuctionStatus.OPEN && now.isBefore(endsAt);
    }
}
