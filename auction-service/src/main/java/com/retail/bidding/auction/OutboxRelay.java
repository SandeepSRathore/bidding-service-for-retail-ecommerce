package com.retail.bidding.auction;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.retail.bidding.auction.OutboxRepository.OutboxMessage;
import com.retail.bidding.events.Topics;

/**
 * Publishes outbox rows to Kafka. A row is marked published only after the broker acknowledges it,
 * so a crash in between republishes it (at-least-once); consumers de-duplicate by eventId.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${auction.outbox-poll-interval:PT1S}")
    @Transactional
    public void publishPending() throws Exception {
        List<OutboxMessage> batch = outbox.lockUnpublished(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxMessage message : batch) {
            var record = new ProducerRecord<>(message.topic(), message.aggregateId().toString(), message.payload());
            record.headers().add(Topics.EVENT_TYPE_HEADER, message.eventType().getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(10, TimeUnit.SECONDS);
        }
        outbox.markPublished(batch.stream().map(OutboxMessage::id).toList(), clock.instant());
        log.debug("Published {} outbox message(s)", batch.size());
    }
}
