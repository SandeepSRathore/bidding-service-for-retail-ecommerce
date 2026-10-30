package com.retail.bidding.auction;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AuctionRepository {

    private final JdbcClient jdbc;

    AuctionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Auction auction, List<UUID> orderIds) {
        jdbc.sql("""
                INSERT INTO auction (id, status, starts_at, ends_at, created_by, created_at)
                VALUES (:id, :status, :startsAt, :endsAt, :createdBy, :createdAt)
                """)
                .param("id", auction.id())
                .param("status", auction.status().name())
                .param("startsAt", Timestamp.from(auction.startsAt()))
                .param("endsAt", Timestamp.from(auction.endsAt()))
                .param("createdBy", auction.createdBy())
                .param("createdAt", Timestamp.from(auction.createdAt()))
                .update();
        for (UUID orderId : orderIds) {
            jdbc.sql("INSERT INTO auction_order (auction_id, order_id) VALUES (:auctionId, :orderId)")
                    .param("auctionId", auction.id())
                    .param("orderId", orderId)
                    .update();
        }
    }

    Optional<Auction> findById(UUID id) {
        return jdbc.sql("SELECT * FROM auction WHERE id = :id")
                .param("id", id)
                .query(AuctionRepository::map)
                .optional();
    }

    List<Auction> findByStatus(AuctionStatus status) {
        return jdbc.sql("SELECT * FROM auction WHERE status = :status ORDER BY ends_at")
                .param("status", status.name())
                .query(AuctionRepository::map)
                .list();
    }

    List<UUID> findOrderIds(UUID auctionId) {
        return jdbc.sql("SELECT order_id FROM auction_order WHERE auction_id = :auctionId ORDER BY order_id")
                .param("auctionId", auctionId)
                .query(UUID.class)
                .list();
    }

    /**
     * Shared row lock taken by bid writes: bids don't block each other, but they do block (and are
     * blocked by) the closer's exclusive lock, so no bid can commit after the window is closed.
     */
    Optional<Auction> lockForBid(UUID id) {
        return jdbc.sql("SELECT * FROM auction WHERE id = :id FOR SHARE")
                .param("id", id)
                .query(AuctionRepository::map)
                .optional();
    }

    /**
     * Claims the next open window whose deadline has passed. SKIP LOCKED lets several instances run
     * the closer at once without picking the same window, and without waiting on in-flight bids.
     */
    Optional<Auction> lockNextDue(Instant now) {
        return jdbc.sql("""
                SELECT * FROM auction
                WHERE status = 'OPEN' AND ends_at <= :now
                ORDER BY ends_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """)
                .param("now", Timestamp.from(now))
                .query(AuctionRepository::map)
                .optional();
    }

    void markAwarded(UUID id, UUID winnerVendorId, BigDecimal pricePerOrder, Instant closedAt) {
        jdbc.sql("""
                UPDATE auction SET status = 'AWARDED', winner_vendor_id = :winner,
                                   winning_price_per_order = :price, closed_at = :closedAt
                WHERE id = :id
                """)
                .param("id", id)
                .param("winner", winnerVendorId)
                .param("price", pricePerOrder)
                .param("closedAt", Timestamp.from(closedAt))
                .update();
    }

    void markClosedWithoutBids(UUID id, Instant closedAt) {
        jdbc.sql("UPDATE auction SET status = 'CLOSED', closed_at = :closedAt WHERE id = :id")
                .param("id", id)
                .param("closedAt", Timestamp.from(closedAt))
                .update();
    }

    private static Auction map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new Auction(
                rs.getObject("id", UUID.class),
                AuctionStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),
                rs.getObject("winner_vendor_id", UUID.class),
                rs.getBigDecimal("winning_price_per_order"),
                closedAt == null ? null : closedAt.toInstant(),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }
}
