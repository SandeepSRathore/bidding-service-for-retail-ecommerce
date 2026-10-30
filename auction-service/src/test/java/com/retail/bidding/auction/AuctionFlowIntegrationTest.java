package com.retail.bidding.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.retail.bidding.events.AuctionAwarded;
import com.retail.bidding.events.AuctionOpened;
import com.retail.bidding.events.Topics;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "auction.window-duration=PT1H",
        "auction.closer-interval=PT1H", // tests trigger the closer themselves
        "auction.outbox-poll-interval=PT0.2S",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AuctionFlowIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    MutableClock clock;

    @Autowired
    AuctionCloser closer;

    @Autowired
    AuctionRepository auctions;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @MockitoBean
    OrderClient orderClient;

    @MockitoBean
    VendorClient vendorClient;

    private Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        consumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), "test");
        consumer.subscribe(List.of(Topics.AUCTION_OPENED, Topics.AUCTION_AWARDED));
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    @Test
    void twoOrdersTenVendorsLowestPricePerOrderWinsEverything() throws Exception {
        List<UUID> orderIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        UUID auctionId = openAuction(orderIds);
        verify(orderClient).lockOrders(eq(auctionId), any());

        AuctionOpened opened = awaitEvent(Topics.AUCTION_OPENED, auctionId, AuctionOpened.class);
        assertThat(opened.orderCount()).isEqualTo(2);
        assertThat(Duration.between(opened.startsAt(), opened.endsAt())).isEqualTo(Duration.ofHours(1));

        // Ten vendors bid 200, 190, ..., 110 per order.
        List<UUID> vendors = IntStream.range(0, 10).mapToObj(i -> UUID.randomUUID()).toList();
        for (int i = 0; i < vendors.size(); i++) {
            bid(auctionId, vendors.get(i), (200 - i * 10) + ".00").andExpect(status().isOk());
        }
        // Vendor 0 revises from 200 down to 105, undercutting everyone.
        clock.advance(Duration.ofMinutes(30));
        bid(auctionId, vendors.get(0), "105.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerOrder").value(105.00));
        // Vendor 5 matches 105 later: it loses the tie because vendor 0 got there first.
        clock.advance(Duration.ofMinutes(1));
        bid(auctionId, vendors.get(5), "105.00").andExpect(status().isOk());

        // The window is sealed: a vendor only sees its own bid, and no winner is shown yet.
        mvc.perform(get("/auctions/{id}/bid", auctionId).headers(vendorHeaders(vendors.get(3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerOrder").value(170.00));
        mvc.perform(get("/auctions/{id}", auctionId).headers(vendorHeaders(vendors.get(3))))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.winnerVendorId").doesNotExist());

        // Deadline passes. Bids are refused even before the closer has run.
        clock.advance(Duration.ofMinutes(29));
        bid(auctionId, vendors.get(9), "1.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_CLOSED"));

        closer.closeDueAuctions();

        AuctionAwarded awarded = awaitEvent(Topics.AUCTION_AWARDED, auctionId, AuctionAwarded.class);
        assertThat(awarded.winnerVendorId()).isEqualTo(vendors.get(0));
        assertThat(awarded.pricePerOrder()).isEqualByComparingTo("105.00");
        assertThat(awarded.totalCharge()).isEqualByComparingTo("210.00");
        assertThat(awarded.orderIds()).containsExactlyInAnyOrderElementsOf(orderIds);
        assertThat(awarded.participantVendorIds()).containsExactlyInAnyOrderElementsOf(vendors);

        mvc.perform(get("/auctions/{id}", auctionId).headers(adminHeaders()))
                .andExpect(jsonPath("$.status").value("AWARDED"))
                .andExpect(jsonPath("$.winnerVendorId").value(vendors.get(0).toString()))
                .andExpect(jsonPath("$.winningPricePerOrder").value(105.00));
    }

    @Test
    void closerWaitsForAnInFlightBidThenAwardsIncludingIt() throws Exception {
        UUID auctionId = openAuction(List.of(UUID.randomUUID()));
        UUID vendor = UUID.randomUUID();
        clock.advance(Duration.ofMinutes(59));

        // Simulate a bid transaction that took its lock before the deadline and is still running.
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> inFlightBid = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            auctions.lockForBid(auctionId);
            locked.countDown();
            awaitLatch(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        clock.advance(Duration.ofMinutes(2));
        closer.closeDueAuctions();
        assertThat(auctions.findById(auctionId)).get().extracting(Auction::status).isEqualTo(AuctionStatus.OPEN);

        release.countDown();
        inFlightBid.get(10, TimeUnit.SECONDS);
        bid(auctionId, vendor, "50.00").andExpect(status().isConflict()); // after the deadline: refused

        closer.closeDueAuctions();
        assertThat(auctions.findById(auctionId)).get().extracting(Auction::status).isEqualTo(AuctionStatus.CLOSED);
        verify(orderClient).releaseOrders(auctionId);
    }

    @Test
    void rejectsInvalidBidsAndWrongRoles() throws Exception {
        UUID auctionId = openAuction(List.of(UUID.randomUUID()));
        UUID vendor = UUID.randomUUID();

        bid(auctionId, vendor, "0.00").andExpect(status().isBadRequest());
        bid(auctionId, vendor, "10.123").andExpect(status().isBadRequest());
        bid(UUID.randomUUID(), vendor, "10.00").andExpect(status().isNotFound());

        mvc.perform(put("/auctions/{id}/bid", auctionId).headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pricePerOrder\":10}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auctions").headers(vendorHeaders(vendor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/auctions/{id}", auctionId)).andExpect(status().isUnauthorized());

        UUID inactive = UUID.randomUUID();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "VENDOR_NOT_ACTIVE", "inactive"))
                .when(vendorClient).requireActive(inactive);
        bid(auctionId, inactive, "10.00")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VENDOR_NOT_ACTIVE"));
    }

    @Test
    void openingFailsCleanlyWhenOrdersAreUnavailable() throws Exception {
        doThrow(new ApiException(HttpStatus.CONFLICT, "ORDERS_UNAVAILABLE", "already in another window"))
                .when(orderClient).lockOrders(any(), any());

        mvc.perform(post("/auctions").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDERS_UNAVAILABLE"));
    }

    private UUID openAuction(List<UUID> orderIds) throws Exception {
        String body = mvc.perform(post("/auctions").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuctionController.OpenAuctionRequest(orderIds))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private ResultActions bid(UUID auctionId, UUID vendorId, String price) throws Exception {
        return mvc.perform(put("/auctions/{id}/bid", auctionId).headers(vendorHeaders(vendorId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pricePerOrder\":" + price + "}"));
    }

    private <T> T awaitEvent(String topic, UUID auctionId, Class<T> type) {
        return await().atMost(Duration.ofSeconds(20)).until(() -> {
            consumer.poll(Duration.ofMillis(200)).forEach(received::add);
            return received.stream()
                    .filter(r -> r.topic().equals(topic) && r.key().equals(auctionId.toString()))
                    .map(r -> json.readValue(r.value(), type))
                    .findFirst()
                    .orElse(null);
        }, java.util.Objects::nonNull);
    }

    private static org.springframework.http.HttpHeaders adminHeaders() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.add(Caller.ID_HEADER, "admin-1");
        headers.add(Caller.ROLE_HEADER, Caller.ADMIN);
        return headers;
    }

    private static org.springframework.http.HttpHeaders vendorHeaders(UUID vendorId) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.add(Caller.ID_HEADER, vendorId.toString());
        headers.add(Caller.ROLE_HEADER, Caller.VENDOR);
        return headers;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
