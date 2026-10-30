package com.retail.bidding.notification;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.retail.bidding.events.Topics;

import tools.jackson.core.JacksonException;

@Configuration
class KafkaConfig {

    static final String DLT_SUFFIX = ".DLT";

    @Bean
    NewTopic auctionOpenedDlt() {
        return TopicBuilder.name(Topics.AUCTION_OPENED + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic auctionAwardedDlt() {
        return TopicBuilder.name(Topics.AUCTION_AWARDED + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    /** Retries with exponential backoff, then parks the record on {@code <topic>.DLT}. Bad JSON is not retried. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
