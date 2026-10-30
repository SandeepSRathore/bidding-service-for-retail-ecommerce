package com.retail.bidding.order;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.retail.bidding.events.AuctionAwarded;

@Service
class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final Clock clock;

    OrderService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * Locks orders to one bidding window. Safe to retry with the same auctionId; fails if any order
     * is missing or already belongs to another window.
     */
    @Transactional
    List<Order> lockForAuction(UUID auctionId, List<UUID> orderIds) {
        Set<UUID> requested = new HashSet<>(orderIds);
        List<Order> found = orders.lockByIds(requested);

        if (found.size() != requested.size()) {
            Set<UUID> missing = new HashSet<>(requested);
            found.forEach(o -> missing.remove(o.id()));
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Orders not found: " + missing);
        }
        List<UUID> unavailable = found.stream()
                .filter(o -> !(o.status() == OrderStatus.PENDING
                        || (o.status() == OrderStatus.IN_AUCTION && auctionId.equals(o.auctionId()))))
                .map(Order::id)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Orders already in another bidding window or assigned: " + unavailable);
        }

        orders.markInAuction(requested, auctionId, clock.instant());
        return orders.findByAuctionId(auctionId);
    }

    @Transactional
    int releaseAuction(UUID auctionId) {
        return orders.releaseAuction(auctionId, clock.instant());
    }

    /** Assigns every order of an awarded window to the winning vendor. Duplicate events are ignored. */
    @Transactional
    void onAuctionAwarded(AuctionAwarded event) {
        if (!orders.markEventProcessed(event.eventId(), clock.instant())) {
            log.info("Skipping duplicate event {} for auction {}", event.eventId(), event.auctionId());
            return;
        }
        int assigned = orders.assign(event.auctionId(), event.orderIds(), event.winnerVendorId(),
                event.pricePerOrder(), clock.instant());
        log.info("Auction {} awarded: assigned {}/{} orders to vendor {} at {} per order",
                event.auctionId(), assigned, event.orderIds().size(), event.winnerVendorId(),
                event.pricePerOrder());
    }
}
