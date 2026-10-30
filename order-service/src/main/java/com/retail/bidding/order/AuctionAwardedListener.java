package com.retail.bidding.order;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.retail.bidding.events.AuctionAwarded;
import com.retail.bidding.events.Topics;

import tools.jackson.databind.json.JsonMapper;

@Component
class AuctionAwardedListener {

    private final OrderService orderService;
    private final JsonMapper json;

    AuctionAwardedListener(OrderService orderService, JsonMapper json) {
        this.orderService = orderService;
        this.json = json;
    }

    @KafkaListener(topics = Topics.AUCTION_AWARDED)
    void onAuctionAwarded(String payload) {
        orderService.onAuctionAwarded(json.readValue(payload, AuctionAwarded.class));
    }
}
