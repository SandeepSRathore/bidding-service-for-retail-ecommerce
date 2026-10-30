package com.retail.bidding.auction;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Picks the winning bid: lowest price per order; on a tie, the bid submitted (or last revised) first.
 * Vendor id is the final tie-breaker so the result is always deterministic.
 */
final class WinnerSelector {

    static final Comparator<Bid> RANKING = Comparator
            .comparing(Bid::pricePerOrder)
            .thenComparing(Bid::updatedAt)
            .thenComparing(Bid::vendorId);

    private WinnerSelector() {
    }

    static Optional<Bid> select(Collection<Bid> bids) {
        return bids.stream().min(RANKING);
    }
}
