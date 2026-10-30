package com.retail.bidding.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.retail.bidding.events.AuctionAwarded;
import com.retail.bidding.events.AuctionOpened;
import com.retail.bidding.events.Topics;
import com.retail.bidding.notification.OrderClient.OrderSummary;
import com.retail.bidding.notification.VendorClient.VendorContact;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestcontainersConfig.class)
class NotificationIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @MockitoBean
    MailSender mailSender;

    @MockitoBean
    VendorClient vendorClient;

    @MockitoBean
    OrderClient orderClient;

    private final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();
    private List<VendorContact> vendors;

    @BeforeEach
    void setUp() {
        vendors = IntStream.range(0, 10)
                .mapToObj(i -> new VendorContact(UUID.randomUUID(), "Vendor " + i, "vendor" + i + "@example.com"))
                .toList();
        Map<UUID, VendorContact> byId = vendors.stream().collect(Collectors.toMap(VendorContact::id, Function.identity()));
        when(vendorClient.activeVendors()).thenReturn(vendors);
        when(vendorClient.get(any())).thenAnswer(inv -> byId.get(inv.<UUID>getArgument(0)));
        doAnswer(inv -> sent.add(inv.getArgument(0))).when(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void everyActiveVendorIsEmailedOnceWhenAWindowOpensEvenIfTheEventIsRedelivered() {
        UUID auctionId = UUID.randomUUID();
        var event = new AuctionOpened(UUID.randomUUID(), auctionId, List.of(UUID.randomUUID(), UUID.randomUUID()),
                2, Instant.parse("2026-10-31T13:00:00Z"), Instant.parse("2026-10-31T14:00:00Z"));

        publish(Topics.AUCTION_OPENED, auctionId, event);
        publish(Topics.AUCTION_OPENED, auctionId, event);

        await().atMost(Duration.ofSeconds(20)).until(() -> sent.size() >= 10);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 10);
        assertThat(sent).extracting(m -> m.getTo()[0])
                .containsExactlyInAnyOrderElementsOf(vendors.stream().map(VendorContact::email).toList());
        assertThat(sent.getFirst().getSubject()).isEqualTo("Bidding open: 2 order(s), closes 2026-10-31 14:00 UTC");
        assertThat(sent.getFirst().getText()).contains("/auctions/" + auctionId + "/bid");
    }

    @Test
    void winnerGetsTheManifestAndOthersAreToldTheyLost() {
        UUID auctionId = UUID.randomUUID();
        var orders = List.of(
                new OrderSummary(UUID.randomUUID(), "C-1", "Warehouse 7", "221B MG Road", new BigDecimal("2.50")),
                new OrderSummary(UUID.randomUUID(), "C-2", "Warehouse 7", "14 Park Street", new BigDecimal("1.00")));
        when(orderClient.ordersOf(auctionId)).thenReturn(orders);
        VendorContact winner = vendors.get(4);

        publish(Topics.AUCTION_AWARDED, auctionId, awarded(auctionId, orders, winner));

        await().atMost(Duration.ofSeconds(20)).until(() -> sent.size() == 10);
        SimpleMailMessage manifest = sent.stream()
                .filter(m -> m.getTo()[0].equals(winner.email())).findFirst().orElseThrow();
        assertThat(manifest.getSubject()).contains("You won auction").contains("2 deliveries");
        assertThat(manifest.getText()).contains("Total charge:    210.00", "221B MG Road", "14 Park Street");
        assertThat(sent).filteredOn(m -> !m.getTo()[0].equals(winner.email()))
                .hasSize(9)
                .allSatisfy(m -> assertThat(m.getSubject()).endsWith("not selected"));
    }

    @Test
    void aFailedSendIsRetriedWithoutEmailingOtherVendorsTwice() {
        UUID auctionId = UUID.randomUUID();
        VendorContact flaky = vendors.get(6);
        AtomicBoolean failedOnce = new AtomicBoolean();
        doAnswer(inv -> {
            SimpleMailMessage message = inv.getArgument(0);
            if (message.getTo()[0].equals(flaky.email()) && failedOnce.compareAndSet(false, true)) {
                throw new MailSendException("SMTP temporarily unavailable");
            }
            return sent.add(message);
        }).when(mailSender).send(any(SimpleMailMessage.class));

        publish(Topics.AUCTION_OPENED, auctionId, new AuctionOpened(UUID.randomUUID(), auctionId,
                List.of(UUID.randomUUID()), 1, Instant.now(), Instant.now().plusSeconds(3600)));

        await().atMost(Duration.ofSeconds(20)).until(() -> sent.size() >= 10);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 10);
        assertThat(failedOnce).isTrue();
        assertThat(sent).extracting(m -> m.getTo()[0]).doesNotHaveDuplicates();
    }

    private AuctionAwarded awarded(UUID auctionId, List<OrderSummary> orders, VendorContact winner) {
        return new AuctionAwarded(UUID.randomUUID(), auctionId, orders.stream().map(OrderSummary::id).toList(),
                winner.id(), new BigDecimal("105.00"), new BigDecimal("210.00"),
                vendors.stream().map(VendorContact::id).toList(), Instant.now());
    }

    private void publish(String topic, UUID auctionId, Object event) {
        kafka.send(topic, auctionId.toString(), json.writeValueAsString(event));
    }
}
