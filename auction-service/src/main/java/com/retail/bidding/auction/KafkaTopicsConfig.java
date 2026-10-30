package com.retail.bidding.auction;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.retail.bidding.events.Topics;

@Configuration
class KafkaTopicsConfig {

    @Bean
    NewTopic auctionOpenedTopic() {
        return TopicBuilder.name(Topics.AUCTION_OPENED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic auctionAwardedTopic() {
        return TopicBuilder.name(Topics.AUCTION_AWARDED).partitions(3).replicas(1).build();
    }
}
