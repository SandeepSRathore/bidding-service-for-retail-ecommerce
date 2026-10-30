package com.retail.bidding.auction;

import java.util.UUID;

import org.springframework.http.HttpStatus;

/**
 * The authenticated user, as asserted by the API gateway in the {@code X-User-Id} and
 * {@code X-User-Role} headers. The gateway strips these headers from incoming requests and sets them
 * from the validated JWT, and only the gateway is reachable from outside.
 */
record Caller(String id, String role) {

    static final String ID_HEADER = "X-User-Id";
    static final String ROLE_HEADER = "X-User-Role";
    static final String ADMIN = "ADMIN";
    static final String VENDOR = "VENDOR";

    static Caller of(String id, String role) {
        if (id == null || id.isBlank() || role == null || role.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Missing caller identity");
        }
        return new Caller(id, role);
    }

    Caller require(String requiredRole) {
        if (!requiredRole.equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Requires role " + requiredRole);
        }
        return this;
    }

    UUID vendorId() {
        require(VENDOR);
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Vendor id is not a UUID");
        }
    }
}
