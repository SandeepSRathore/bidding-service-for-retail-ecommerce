package com.retail.bidding.auction;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

@Repository
class OutboxRepository {

    record OutboxMessage(UUID id, UUID aggregateId, String topic, String eventType, String payload) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    OutboxRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Must be called inside the transaction that made the state change the event describes. */
    void append(String topic, UUID aggregateId, Object event, Instant now) {
        jdbc.sql("""
                INSERT INTO outbox (id, aggregate_id, topic, event_type, payload, created_at)
                VALUES (:id, :aggregateId, :topic, :eventType, CAST(:payload AS JSONB), :now)
                """)
                .param("id", UUID.randomUUID())
                .param("aggregateId", aggregateId)
                .param("topic", topic)
                .param("eventType", event.getClass().getSimpleName())
                .param("payload", json.writeValueAsString(event))
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Claims a batch of unpublished messages, oldest first, skipping rows another relay holds. */
    List<OutboxMessage> lockUnpublished(int limit) {
        return jdbc.sql("""
                SELECT id, aggregate_id, topic, event_type, payload::text AS payload FROM outbox
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query((rs, n) -> new OutboxMessage(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
    }

    void markPublished(Collection<UUID> ids, Instant now) {
        jdbc.sql("UPDATE outbox SET published_at = :now WHERE id IN (:ids)")
                .param("ids", ids)
                .param("now", Timestamp.from(now))
                .update();
    }
}
