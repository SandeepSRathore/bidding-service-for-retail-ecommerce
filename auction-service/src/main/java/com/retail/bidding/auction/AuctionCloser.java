package com.retail.bidding.auction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.retail.bidding.events.AuctionAwarded;
import com.retail.bidding.events.Topics;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Closes bidding windows whose deadline has passed and awards all their orders to the lowest bid.
 * Each window is closed in its own transaction, so one slow or failing window doesn't roll back others.
 */
@Component
class AuctionCloser {

    private static final Logger log = LoggerFactory.getLogger(AuctionCloser.class);

    private record Closed(UUID auctionId, boolean awarded) {
    }

    private final AuctionRepository auctions;
    private final BidRepository bids;
    private final OutboxRepository outbox;
    private final OrderClient orderClient;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    AuctionCloser(AuctionRepository auctions, BidRepository bids, OutboxRepository outbox, OrderClient orderClient,
                  TransactionTemplate tx, MeterRegistry meters, Clock clock) {
        this.auctions = auctions;
        this.bids = bids;
        this.outbox = outbox;
        this.orderClient = orderClient;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${auction.closer-interval:PT5S}")
    public void closeDueAuctions() {
        Optional<Closed> closed;
        while ((closed = tx.execute(status -> closeNextDue())).isPresent()) {
            if (!closed.get().awarded()) {
                // Nobody bid: hand the orders back so they can go into a new window.
                orderClient.releaseOrders(closed.get().auctionId());
            }
        }
    }

    private Optional<Closed> closeNextDue() {
        Instant now = clock.instant();
        Optional<Auction> due = auctions.lockNextDue(now);
        if (due.isEmpty()) {
            return Optional.empty();
        }
        Auction auction = due.get();
        meters.timer("auction.close.lag").record(Duration.between(auction.endsAt(), now));

        List<Bid> auctionBids = bids.findByAuction(auction.id());
        Optional<Bid> winner = WinnerSelector.select(auctionBids);
        if (winner.isEmpty()) {
            auctions.markClosedWithoutBids(auction.id(), now);
            meters.counter("auction.closed", "outcome", "no_bids").increment();
            log.warn("Auction {} closed without bids; releasing its orders", auction.id());
            return Optional.of(new Closed(auction.id(), false));
        }

        Bid best = winner.get();
        List<UUID> orderIds = auctions.findOrderIds(auction.id());
        BigDecimal total = best.pricePerOrder().multiply(BigDecimal.valueOf(orderIds.size()));
        List<UUID> participants = auctionBids.stream().map(Bid::vendorId).sorted().toList();

        auctions.markAwarded(auction.id(), best.vendorId(), best.pricePerOrder(), now);
        outbox.append(Topics.AUCTION_AWARDED, auction.id(), new AuctionAwarded(UUID.randomUUID(), auction.id(),
                orderIds, best.vendorId(), best.pricePerOrder(), total, participants, now), now);
        meters.counter("auction.closed", "outcome", "awarded").increment();
        log.info("Auction {} awarded to vendor {} at {} per order ({} bids, {} orders, total {})",
                auction.id(), best.vendorId(), best.pricePerOrder(), auctionBids.size(), orderIds.size(), total);
        return Optional.of(new Closed(auction.id(), true));
    }
}
