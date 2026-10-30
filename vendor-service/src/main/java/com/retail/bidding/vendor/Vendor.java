package com.retail.bidding.vendor;

import java.time.Instant;
import java.util.UUID;

public record Vendor(UUID id, String name, String email, String phone, VendorStatus status, Instant createdAt) {
}
