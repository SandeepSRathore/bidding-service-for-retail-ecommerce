package com.retail.bidding.order;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class OrderController {

    record CreateOrderRequest(
            @NotBlank String customerRef,
            @NotBlank String pickupAddress,
            @NotBlank String dropAddress,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal weightKg) {
    }

    record LockRequest(@NotNull UUID auctionId, @NotEmpty List<@NotNull UUID> orderIds) {
    }

    record ReleaseRequest(@NotNull UUID auctionId) {
    }

    private final OrderRepository orders;
    private final OrderService orderService;
    private final Clock clock;

    OrderController(OrderRepository orders, OrderService orderService, Clock clock) {
        this.orders = orders;
        this.orderService = orderService;
        this.clock = clock;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    Order create(@Valid @RequestBody CreateOrderRequest request) {
        var now = clock.instant();
        Order order = new Order(UUID.randomUUID(), request.customerRef(), request.pickupAddress(),
                request.dropAddress(), request.weightKg(), OrderStatus.PENDING, null, null, null, now, now);
        orders.insert(order);
        return order;
    }

    @GetMapping("/orders/{id}")
    Order get(@PathVariable UUID id) {
        return orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order " + id + " not found"));
    }

    @GetMapping("/orders")
    List<Order> list(@RequestParam(required = false) UUID auctionId,
                     @RequestParam(required = false) OrderStatus status) {
        if (auctionId != null) {
            return orders.findByAuctionId(auctionId);
        }
        return status != null ? orders.findByStatus(status) : orders.findAll();
    }

    // Internal endpoints: called by auction-service only, never routed by the gateway.

    @PostMapping("/internal/orders/lock")
    List<Order> lock(@Valid @RequestBody LockRequest request) {
        return orderService.lockForAuction(request.auctionId(), request.orderIds());
    }

    @PostMapping("/internal/orders/release")
    void release(@Valid @RequestBody ReleaseRequest request) {
        orderService.releaseAuction(request.auctionId());
    }
}
