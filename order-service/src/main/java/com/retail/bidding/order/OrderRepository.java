package com.retail.bidding.order;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OrderRepository {

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Order order) {
        jdbc.sql("""
                INSERT INTO orders (id, customer_ref, pickup_address, drop_address, weight_kg, status,
                                    created_at, updated_at)
                VALUES (:id, :customerRef, :pickup, :drop, :weight, :status, :now, :now)
                """)
                .param("id", order.id())
                .param("customerRef", order.customerRef())
                .param("pickup", order.pickupAddress())
                .param("drop", order.dropAddress())
                .param("weight", order.weightKg())
                .param("status", order.status().name())
                .param("now", Timestamp.from(order.createdAt()))
                .update();
    }

    Optional<Order> findById(UUID id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = :id")
                .param("id", id)
                .query(OrderRepository::map)
                .optional();
    }

    List<Order> findByAuctionId(UUID auctionId) {
        return jdbc.sql("SELECT * FROM orders WHERE auction_id = :auctionId ORDER BY created_at")
                .param("auctionId", auctionId)
                .query(OrderRepository::map)
                .list();
    }

    List<Order> findByStatus(OrderStatus status) {
        return jdbc.sql("SELECT * FROM orders WHERE status = :status ORDER BY created_at")
                .param("status", status.name())
                .query(OrderRepository::map)
                .list();
    }

    List<Order> findAll() {
        return jdbc.sql("SELECT * FROM orders ORDER BY created_at").query(OrderRepository::map).list();
    }

    /** Row-locks the given orders for the rest of the transaction. */
    List<Order> lockByIds(Collection<UUID> ids) {
        return jdbc.sql("SELECT * FROM orders WHERE id IN (:ids) FOR UPDATE")
                .param("ids", ids)
                .query(OrderRepository::map)
                .list();
    }

    int markInAuction(Collection<UUID> ids, UUID auctionId, Instant now) {
        return jdbc.sql("""
                UPDATE orders SET status = 'IN_AUCTION', auction_id = :auctionId, updated_at = :now
                WHERE id IN (:ids)
                """)
                .param("ids", ids)
                .param("auctionId", auctionId)
                .param("now", Timestamp.from(now))
                .update();
    }

    int releaseAuction(UUID auctionId, Instant now) {
        return jdbc.sql("""
                UPDATE orders SET status = 'PENDING', auction_id = NULL, updated_at = :now
                WHERE auction_id = :auctionId AND status = 'IN_AUCTION'
                """)
                .param("auctionId", auctionId)
                .param("now", Timestamp.from(now))
                .update();
    }

    int assign(UUID auctionId, Collection<UUID> ids, UUID vendorId, BigDecimal charge, Instant now) {
        return jdbc.sql("""
                UPDATE orders SET status = 'ASSIGNED', vendor_id = :vendorId, delivery_charge = :charge,
                                  updated_at = :now
                WHERE auction_id = :auctionId AND id IN (:ids) AND status = 'IN_AUCTION'
                """)
                .param("auctionId", auctionId)
                .param("ids", ids)
                .param("vendorId", vendorId)
                .param("charge", charge)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Returns false if the event was already recorded, i.e. it is a redelivery. */
    boolean markEventProcessed(UUID eventId, Instant now) {
        return jdbc.sql("""
                INSERT INTO processed_event (event_id, processed_at) VALUES (:eventId, :now)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("eventId", eventId)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    private static Order map(ResultSet rs, int rowNum) throws SQLException {
        return new Order(
                rs.getObject("id", UUID.class),
                rs.getString("customer_ref"),
                rs.getString("pickup_address"),
                rs.getString("drop_address"),
                rs.getBigDecimal("weight_kg"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getObject("auction_id", UUID.class),
                rs.getObject("vendor_id", UUID.class),
                rs.getBigDecimal("delivery_charge"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
