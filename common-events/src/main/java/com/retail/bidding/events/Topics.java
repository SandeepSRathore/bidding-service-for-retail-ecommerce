package com.retail.bidding.events;

/** Kafka topic names. Every topic is keyed by auctionId so a window's events stay ordered. */
public final class Topics {

    public static final String AUCTION_OPENED = "auction.opened";
    public static final String AUCTION_AWARDED = "auction.awarded";

    /** Header carrying the event type, so consumers can route without parsing the payload. */
    public static final String EVENT_TYPE_HEADER = "eventType";

    private Topics() {
    }
}
