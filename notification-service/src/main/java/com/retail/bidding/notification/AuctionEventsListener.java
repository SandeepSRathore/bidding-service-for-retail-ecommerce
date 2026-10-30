package com.retail.bidding.notification;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.retail.bidding.events.AuctionAwarded;
import com.retail.bidding.events.AuctionOpened;
import com.retail.bidding.events.Topics;

import tools.jackson.databind.json.JsonMapper;

@Component
class AuctionEventsListener {

    private final NotificationService notifications;
    private final JsonMapper json;

    AuctionEventsListener(NotificationService notifications, JsonMapper json) {
        this.notifications = notifications;
        this.json = json;
    }

    @KafkaListener(topics = Topics.AUCTION_OPENED)
    void onAuctionOpened(String payload) {
        notifications.onAuctionOpened(json.readValue(payload, AuctionOpened.class));
    }

    @KafkaListener(topics = Topics.AUCTION_AWARDED)
    void onAuctionAwarded(String payload) {
        notifications.onAuctionAwarded(json.readValue(payload, AuctionAwarded.class));
    }
}
