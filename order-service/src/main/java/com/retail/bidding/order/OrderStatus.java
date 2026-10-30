package com.retail.bidding.order;

/** PENDING → IN_AUCTION (locked to one bidding window) → ASSIGNED (to the winning vendor). */
public enum OrderStatus {
    PENDING,
    IN_AUCTION,
    ASSIGNED
}
