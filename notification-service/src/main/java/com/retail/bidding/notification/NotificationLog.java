package com.retail.bidding.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NotificationLog {

    private final JdbcClient jdbc;

    NotificationLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean alreadySent(UUID auctionId, UUID vendorId, NotificationType type) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM notification_log
                               WHERE auction_id = :auctionId AND vendor_id = :vendorId AND type = :type)
                """)
                .param("auctionId", auctionId)
                .param("vendorId", vendorId)
                .param("type", type.name())
                .query(Boolean.class)
                .single();
    }

    void recordSent(UUID auctionId, UUID vendorId, NotificationType type, String recipient, Instant now) {
        jdbc.sql("""
                INSERT INTO notification_log (id, auction_id, vendor_id, type, recipient, sent_at)
                VALUES (:id, :auctionId, :vendorId, :type, :recipient, :now)
                ON CONFLICT (auction_id, vendor_id, type) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("auctionId", auctionId)
                .param("vendorId", vendorId)
                .param("type", type.name())
                .param("recipient", recipient)
                .param("now", Timestamp.from(now))
                .update();
    }

    boolean isProcessed(UUID eventId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM processed_event WHERE event_id = :eventId)")
                .param("eventId", eventId)
                .query(Boolean.class)
                .single();
    }

    void markProcessed(UUID eventId, Instant now) {
        jdbc.sql("""
                INSERT INTO processed_event (event_id, processed_at) VALUES (:eventId, :now)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("eventId", eventId)
                .param("now", Timestamp.from(now))
                .update();
    }
}
