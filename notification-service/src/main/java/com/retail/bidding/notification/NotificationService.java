package com.retail.bidding.notification;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;

import com.retail.bidding.events.AuctionAwarded;
import com.retail.bidding.events.AuctionOpened;
import com.retail.bidding.notification.OrderClient.OrderSummary;
import com.retail.bidding.notification.VendorClient.VendorContact;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Sends one email per vendor per event. If sending fails part-way, the event is retried and vendors
 * already emailed (per notification_log) are skipped, so nobody gets the same email twice.
 */
@Service
class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final VendorClient vendors;
    private final OrderClient orders;
    private final EmailComposer composer;
    private final MailSender mail;
    private final NotificationLog notificationLog;
    private final MeterRegistry meters;
    private final Clock clock;

    NotificationService(VendorClient vendors, OrderClient orders, EmailComposer composer, MailSender mail,
                        NotificationLog notificationLog, MeterRegistry meters, Clock clock) {
        this.vendors = vendors;
        this.orders = orders;
        this.composer = composer;
        this.mail = mail;
        this.notificationLog = notificationLog;
        this.meters = meters;
        this.clock = clock;
    }

    void onAuctionOpened(AuctionOpened event) {
        if (notificationLog.isProcessed(event.eventId())) {
            return;
        }
        List<VendorContact> active = vendors.activeVendors();
        for (VendorContact vendor : active) {
            send(event.auctionId(), vendor, NotificationType.AUCTION_OPENED, () -> composer.auctionOpened(vendor, event));
        }
        notificationLog.markProcessed(event.eventId(), clock.instant());
        log.info("Notified {} active vendor(s) that auction {} is open", active.size(), event.auctionId());
    }

    void onAuctionAwarded(AuctionAwarded event) {
        if (notificationLog.isProcessed(event.eventId())) {
            return;
        }
        VendorContact winner = vendors.get(event.winnerVendorId());
        List<OrderSummary> manifest = orders.ordersOf(event.auctionId());
        send(event.auctionId(), winner, NotificationType.AWARDED, () -> composer.awarded(winner, event, manifest));

        for (UUID vendorId : event.participantVendorIds()) {
            if (!vendorId.equals(event.winnerVendorId())
                    && !notificationLog.alreadySent(event.auctionId(), vendorId, NotificationType.NOT_SELECTED)) {
                VendorContact loser = vendors.get(vendorId);
                send(event.auctionId(), loser, NotificationType.NOT_SELECTED, () -> composer.notSelected(loser, event));
            }
        }
        notificationLog.markProcessed(event.eventId(), clock.instant());
        log.info("Sent award results for auction {} to {} participant(s)", event.auctionId(),
                event.participantVendorIds().size());
    }

    private void send(UUID auctionId, VendorContact vendor, NotificationType type,
                      Supplier<SimpleMailMessage> message) {
        if (notificationLog.alreadySent(auctionId, vendor.id(), type)) {
            return;
        }
        try {
            mail.send(message.get());
        } catch (RuntimeException e) {
            meters.counter("notification.emails", "type", type.name(), "result", "failed").increment();
            throw e;
        }
        notificationLog.recordSent(auctionId, vendor.id(), type, vendor.email(), clock.instant());
        meters.counter("notification.emails", "type", type.name(), "result", "sent").increment();
    }
}
