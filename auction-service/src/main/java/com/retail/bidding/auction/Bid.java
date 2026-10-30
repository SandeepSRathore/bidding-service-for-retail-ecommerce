package com.retail.bidding.auction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Bid(UUID id, UUID auctionId, UUID vendorId, BigDecimal pricePerOrder, Instant createdAt,
                  Instant updatedAt) {
}
