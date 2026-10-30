package com.retail.bidding.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WinnerSelectorTest {

    private static final UUID AUCTION = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-31T10:00:00Z");

    @Test
    void lowestPricePerOrderWins() {
        Bid a = bid("120.00", 1);
        Bid b = bid("99.99", 5);
        Bid c = bid("150.00", 0);

        assertThat(WinnerSelector.select(List.of(a, b, c))).contains(b);
    }

    @Test
    void tieGoesToTheBidPlacedOrRevisedFirst() {
        Bid early = bid("100.00", 10);
        Bid late = bid("100.00", 20);

        assertThat(WinnerSelector.select(List.of(late, early))).contains(early);
    }

    @Test
    void priceComparisonIgnoresScale() {
        Bid twoDecimals = bid("100.00", 10);
        Bid noDecimals = bid("100", 20);

        assertThat(WinnerSelector.select(List.of(noDecimals, twoDecimals))).contains(twoDecimals);
    }

    @Test
    void noBidsMeansNoWinner() {
        assertThat(WinnerSelector.select(List.of())).isEmpty();
    }

    private static Bid bid(String price, int secondsAfterOpen) {
        Instant at = T0.plusSeconds(secondsAfterOpen);
        return new Bid(UUID.randomUUID(), AUCTION, UUID.randomUUID(), new BigDecimal(price), at, at);
    }
}
