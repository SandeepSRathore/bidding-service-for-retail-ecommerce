package com.retail.bidding.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Published when a bidding window opens. Vendors are notified so they can start bidding. */
public record AuctionOpened(
        UUID eventId,
        UUID auctionId,
        List<UUID> orderIds,
        int orderCount,
        Instant startsAt,
        Instant endsAt) {
}
