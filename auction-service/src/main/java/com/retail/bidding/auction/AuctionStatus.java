package com.retail.bidding.auction;

/** OPEN → AWARDED when the window closes with bids, or OPEN → CLOSED when it closes with none. */
public enum AuctionStatus {
    OPEN,
    AWARDED,
    CLOSED
}
