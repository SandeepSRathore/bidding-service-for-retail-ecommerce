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
class BidRepository {

    private final JdbcClient jdbc;

    BidRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the vendor's bid, or revises it in place; {@code updated_at} moves on every revision. */
    Bid upsert(UUID auctionId, UUID vendorId, BigDecimal pricePerOrder, Instant now) {
        return jdbc.sql("""
                INSERT INTO bid (id, auction_id, vendor_id, price_per_order, created_at, updated_at)
                VALUES (:id, :auctionId, :vendorId, :price, :now, :now)
                ON CONFLICT (auction_id, vendor_id)
                DO UPDATE SET price_per_order = EXCLUDED.price_per_order, updated_at = EXCLUDED.updated_at
                RETURNING *
                """)
                .param("id", UUID.randomUUID())
                .param("auctionId", auctionId)
                .param("vendorId", vendorId)
                .param("price", pricePerOrder)
                .param("now", Timestamp.from(now))
                .query(BidRepository::map)
                .single();
    }

    Optional<Bid> find(UUID auctionId, UUID vendorId) {
        return jdbc.sql("SELECT * FROM bid WHERE auction_id = :auctionId AND vendor_id = :vendorId")
                .param("auctionId", auctionId)
                .param("vendorId", vendorId)
                .query(BidRepository::map)
                .optional();
    }

    List<Bid> findByAuction(UUID auctionId) {
        return jdbc.sql("SELECT * FROM bid WHERE auction_id = :auctionId")
                .param("auctionId", auctionId)
                .query(BidRepository::map)
                .list();
    }

    private static Bid map(ResultSet rs, int rowNum) throws SQLException {
        return new Bid(
                rs.getObject("id", UUID.class),
                rs.getObject("auction_id", UUID.class),
                rs.getObject("vendor_id", UUID.class),
                rs.getBigDecimal("price_per_order"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
