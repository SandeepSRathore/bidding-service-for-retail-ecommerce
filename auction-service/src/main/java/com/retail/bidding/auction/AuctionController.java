package com.retail.bidding.auction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auctions")
class AuctionController {

    record OpenAuctionRequest(@NotEmpty @Size(max = 500) List<@NotNull UUID> orderIds) {
    }

    record BidRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal pricePerOrder) {
    }

    /** The winner fields stay null until the window is awarded; other vendors' bids are never exposed. */
    record AuctionView(UUID id, AuctionStatus status, Instant startsAt, Instant endsAt, List<UUID> orderIds,
                       UUID winnerVendorId, BigDecimal winningPricePerOrder) {
    }

    record BidView(UUID auctionId, UUID vendorId, BigDecimal pricePerOrder, Instant submittedAt,
                   Instant updatedAt) {

        static BidView of(Bid bid) {
            return new BidView(bid.auctionId(), bid.vendorId(), bid.pricePerOrder(), bid.createdAt(),
                    bid.updatedAt());
        }
    }

    private final AuctionService auctionService;

    AuctionController(AuctionService auctionService) {
        this.auctionService = auctionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AuctionView open(@RequestHeader(name = Caller.ID_HEADER, required = false) String userId,
                     @RequestHeader(name = Caller.ROLE_HEADER, required = false) String role,
                     @Valid @RequestBody OpenAuctionRequest request) {
        Caller admin = Caller.of(userId, role).require(Caller.ADMIN);
        return view(auctionService.open(request.orderIds(), admin.id()));
    }

    @GetMapping
    List<AuctionView> list(@RequestHeader(name = Caller.ID_HEADER, required = false) String userId,
                           @RequestHeader(name = Caller.ROLE_HEADER, required = false) String role,
                           @RequestParam(defaultValue = "OPEN") AuctionStatus status) {
        Caller.of(userId, role);
        return auctionService.list(status).stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    AuctionView get(@RequestHeader(name = Caller.ID_HEADER, required = false) String userId,
                    @RequestHeader(name = Caller.ROLE_HEADER, required = false) String role,
                    @PathVariable UUID id) {
        Caller.of(userId, role);
        return view(auctionService.get(id));
    }

    @PutMapping("/{id}/bid")
    BidView placeBid(@RequestHeader(name = Caller.ID_HEADER, required = false) String userId,
                     @RequestHeader(name = Caller.ROLE_HEADER, required = false) String role,
                     @PathVariable UUID id,
                     @Valid @RequestBody BidRequest request) {
        UUID vendorId = Caller.of(userId, role).vendorId();
        return BidView.of(auctionService.placeBid(id, vendorId, request.pricePerOrder()));
    }

    @GetMapping("/{id}/bid")
    BidView myBid(@RequestHeader(name = Caller.ID_HEADER, required = false) String userId,
                  @RequestHeader(name = Caller.ROLE_HEADER, required = false) String role,
                  @PathVariable UUID id) {
        UUID vendorId = Caller.of(userId, role).vendorId();
        return auctionService.findBid(id, vendorId)
                .map(BidView::of)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BID_NOT_FOUND",
                        "You have not bid on auction " + id));
    }

    private AuctionView view(Auction auction) {
        return new AuctionView(auction.id(), auction.status(), auction.startsAt(), auction.endsAt(),
                auctionService.orderIds(auction.id()), auction.winnerVendorId(), auction.winningPricePerOrder());
    }
}
