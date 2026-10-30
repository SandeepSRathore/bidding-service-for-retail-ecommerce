package com.retail.bidding.notification;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

import com.retail.bidding.events.AuctionAwarded;
import com.retail.bidding.events.AuctionOpened;
import com.retail.bidding.notification.OrderClient.OrderSummary;
import com.retail.bidding.notification.VendorClient.VendorContact;

/** Builds the plain-text emails. Kept free of I/O so the wording is easy to test and change. */
@Component
class EmailComposer {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final NotificationProperties properties;

    EmailComposer(NotificationProperties properties) {
        this.properties = properties;
    }

    SimpleMailMessage auctionOpened(VendorContact vendor, AuctionOpened event) {
        return message(vendor,
                "Bidding open: %d order(s), closes %s".formatted(event.orderCount(), TIME.format(event.endsAt())),
                """
                Hello %s,

                A delivery bidding window is now open.

                  Auction:   %s
                  Orders:    %d
                  Closes at: %s

                Quote ONE price per order; it applies to every order in this window. The vendor with the
                lowest price per order when the window closes is assigned all of the orders.
                Bids are sealed: other vendors cannot see your price. You can revise it until the window closes.

                Place or revise your bid:
                  PUT %s/auctions/%s/bid   {"pricePerOrder": 120.00}
                """.formatted(vendor.name(), event.auctionId(), event.orderCount(), TIME.format(event.endsAt()),
                        properties.biddingPortalUrl(), event.auctionId()));
    }

    SimpleMailMessage awarded(VendorContact vendor, AuctionAwarded event, List<OrderSummary> orders) {
        StringBuilder manifest = new StringBuilder();
        for (OrderSummary order : orders) {
            manifest.append("""
                      - Order %s (customer %s, %s kg)
                        Pickup: %s
                        Drop:   %s
                    """.formatted(order.id(), order.customerRef(), order.weightKg().toPlainString(),
                    order.pickupAddress(), order.dropAddress()));
        }
        return message(vendor,
                "You won auction %s: %d deliveries assigned".formatted(event.auctionId(), event.orderIds().size()),
                """
                Hello %s,

                Your bid won auction %s. The following deliveries are now assigned to you.

                  Price per order: %s
                  Orders:          %d
                  Total charge:    %s

                Delivery manifest:
                %s
                """.formatted(vendor.name(), event.auctionId(), event.pricePerOrder().toPlainString(),
                        event.orderIds().size(), event.totalCharge().toPlainString(), manifest));
    }

    SimpleMailMessage notSelected(VendorContact vendor, AuctionAwarded event) {
        return message(vendor,
                "Auction %s result: not selected".formatted(event.auctionId()),
                """
                Hello %s,

                Thank you for bidding on auction %s. Another vendor quoted a lower price per order,
                so the deliveries in this window were not assigned to you.

                You will be notified when the next bidding window opens.
                """.formatted(vendor.name(), event.auctionId()));
    }

    private SimpleMailMessage message(VendorContact vendor, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.fromAddress());
        message.setTo(vendor.email());
        message.setSubject(subject);
        message.setText(body);
        return message;
    }
}
