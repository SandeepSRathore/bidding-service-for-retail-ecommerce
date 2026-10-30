package com.retail.bidding.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.retail.bidding.events.AuctionAwarded;
import com.retail.bidding.events.Topics;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OrderServiceIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    OrderRepository orders;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Test
    void lockIsIdempotentPerAuctionAndExclusiveAcrossAuctions() throws Exception {
        UUID o1 = createOrder();
        UUID o2 = createOrder();
        UUID auctionA = UUID.randomUUID();

        lock(auctionA, List.of(o1, o2)).andExpect(status().isOk());
        lock(auctionA, List.of(o1, o2)).andExpect(status().isOk());
        lock(UUID.randomUUID(), List.of(o1)).andExpect(status().isConflict());
        lock(UUID.randomUUID(), List.of(UUID.randomUUID())).andExpect(status().isNotFound());

        assertThat(orders.findByAuctionId(auctionA))
                .extracting(Order::status)
                .containsOnly(OrderStatus.IN_AUCTION);
    }

    @Test
    void releaseReturnsOrdersToPending() throws Exception {
        UUID o1 = createOrder();
        UUID auction = UUID.randomUUID();
        lock(auction, List.of(o1)).andExpect(status().isOk());

        mvc.perform(post("/internal/orders/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionId\":\"" + auction + "\"}"))
                .andExpect(status().isOk());

        assertThat(orders.findById(o1)).get().extracting(Order::status).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void awardedEventAssignsOrdersToWinnerExactlyOnce() throws Exception {
        UUID o1 = createOrder();
        UUID o2 = createOrder();
        UUID auction = UUID.randomUUID();
        UUID winner = UUID.randomUUID();
        lock(auction, List.of(o1, o2)).andExpect(status().isOk());

        var event = new AuctionAwarded(UUID.randomUUID(), auction, List.of(o1, o2), winner,
                new BigDecimal("95.50"), new BigDecimal("191.00"), List.of(winner), Instant.now());
        String payload = json.writeValueAsString(event);
        kafka.send(Topics.AUCTION_AWARDED, auction.toString(), payload);
        kafka.send(Topics.AUCTION_AWARDED, auction.toString(), payload); // simulated redelivery

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(orders.findByAuctionId(auction)).allSatisfy(o -> {
                    assertThat(o.status()).isEqualTo(OrderStatus.ASSIGNED);
                    assertThat(o.vendorId()).isEqualTo(winner);
                    assertThat(o.deliveryCharge()).isEqualByComparingTo("95.50");
                }));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = :id")
                        .param("id", event.eventId()).query(Long.class).single()).isEqualTo(1L));
    }

    private UUID createOrder() throws Exception {
        String body = mvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerRef":"C-1","pickupAddress":"Warehouse 7, Pune",
                                 "dropAddress":"221B MG Road, Pune","weightKg":2.5}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private org.springframework.test.web.servlet.ResultActions lock(UUID auctionId, List<UUID> orderIds)
            throws Exception {
        return mvc.perform(post("/internal/orders/lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new OrderController.LockRequest(auctionId, orderIds))));
    }
}
