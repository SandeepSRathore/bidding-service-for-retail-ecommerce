package com.retail.bidding.auction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.retail.bidding.events.AuctionOpened;
import com.retail.bidding.events.Topics;

import io.micrometer.core.instrument.MeterRegistry;

@Service
class AuctionService {

    private static final Logger log = LoggerFactory.getLogger(AuctionService.class);

    private final AuctionRepository auctions;
    private final BidRepository bids;
    private final OutboxRepository outbox;
    private final OrderClient orderClient;
    private final VendorClient vendorClient;
    private final AuctionProperties properties;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    AuctionService(AuctionRepository auctions, BidRepository bids, OutboxRepository outbox, OrderClient orderClient,
                   VendorClient vendorClient, AuctionProperties properties, TransactionTemplate tx,
                   MeterRegistry meters, Clock clock) {
        this.auctions = auctions;
        this.bids = bids;
        this.outbox = outbox;
        this.orderClient = orderClient;
        this.vendorClient = vendorClient;
        this.properties = properties;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * Opens a bidding window for the given orders. Orders are first locked in order-service so they
     * can't join two windows; if saving the window then fails, the lock is released again.
     */
    Auction open(List<UUID> orderIds, String createdBy) {
        List<UUID> distinctOrderIds = orderIds.stream().distinct().sorted().toList();
        UUID auctionId = UUID.randomUUID();

        orderClient.lockOrders(auctionId, distinctOrderIds);

        Instant now = clock.instant();
        Auction auction = new Auction(auctionId, AuctionStatus.OPEN, now, now.plus(properties.windowDuration()),
                null, null, null, createdBy, now);
        try {
            tx.executeWithoutResult(status -> {
                auctions.insert(auction, distinctOrderIds);
                outbox.append(Topics.AUCTION_OPENED, auctionId, new AuctionOpened(UUID.randomUUID(), auctionId,
                        distinctOrderIds, distinctOrderIds.size(), auction.startsAt(), auction.endsAt()), now);
            });
        } catch (RuntimeException e) {
            releaseQuietly(auctionId, e);
            throw e;
        }
        log.info("Opened auction {} for {} order(s), closes at {}", auctionId, distinctOrderIds.size(),
                auction.endsAt());
        return auction;
    }

    /** Submits or revises the vendor's sealed bid. Rejected once the window's deadline has passed. */
    Bid placeBid(UUID auctionId, UUID vendorId, BigDecimal pricePerOrder) {
        try {
            vendorClient.requireActive(vendorId);
            Bid bid = tx.execute(status -> {
                Auction auction = auctions.lockForBid(auctionId).orElseThrow(() -> notFound(auctionId));
                Instant now = clock.instant();
                if (!auction.acceptsBidsAt(now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "AUCTION_CLOSED",
                            "Bidding window closed at " + auction.endsAt());
                }
                return bids.upsert(auctionId, vendorId, pricePerOrder, now);
            });
            meters.counter("auction.bids", "result", "accepted").increment();
            return bid;
        } catch (ApiException e) {
            meters.counter("auction.bids", "result", "rejected").increment();
            throw e;
        }
    }

    Auction get(UUID auctionId) {
        return auctions.findById(auctionId).orElseThrow(() -> notFound(auctionId));
    }

    List<Auction> list(AuctionStatus status) {
        return auctions.findByStatus(status);
    }

    List<UUID> orderIds(UUID auctionId) {
        return auctions.findOrderIds(auctionId);
    }

    Optional<Bid> findBid(UUID auctionId, UUID vendorId) {
        get(auctionId);
        return bids.find(auctionId, vendorId);
    }

    private void releaseQuietly(UUID auctionId, RuntimeException cause) {
        try {
            orderClient.releaseOrders(auctionId);
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
            log.error("Could not release orders of failed auction {}; they stay IN_AUCTION", auctionId, e);
        }
    }

    private static ApiException notFound(UUID auctionId) {
        return new ApiException(HttpStatus.NOT_FOUND, "AUCTION_NOT_FOUND", "Auction " + auctionId + " not found");
    }
}
