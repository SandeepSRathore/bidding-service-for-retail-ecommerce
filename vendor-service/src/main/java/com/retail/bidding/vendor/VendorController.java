package com.retail.bidding.vendor;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/vendors")
class VendorController {

    record CreateVendorRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email String email,
            @Size(max = 32) String phone) {
    }

    record UpdateStatusRequest(@NotNull VendorStatus status) {
    }

    private final VendorRepository vendors;
    private final Clock clock;

    VendorController(VendorRepository vendors, Clock clock) {
        this.vendors = vendors;
        this.clock = clock;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Vendor create(@Valid @RequestBody CreateVendorRequest request) {
        Vendor vendor = new Vendor(UUID.randomUUID(), request.name(), request.email().toLowerCase(),
                request.phone(), VendorStatus.ACTIVE, clock.instant());
        try {
            vendors.insert(vendor);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A vendor with this email already exists");
        }
        return vendor;
    }

    @GetMapping
    List<Vendor> list(@RequestParam(required = false) VendorStatus status) {
        return vendors.findAll(status);
    }

    @GetMapping("/{id}")
    Vendor get(@PathVariable UUID id) {
        return vendors.findById(id).orElseThrow(() -> notFound(id));
    }

    @PatchMapping("/{id}/status")
    Vendor updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        if (!vendors.updateStatus(id, request.status())) {
            throw notFound(id);
        }
        return get(id);
    }

    private static ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Vendor " + id + " not found");
    }
}
