CREATE TABLE orders (
    id              UUID PRIMARY KEY,
    customer_ref    VARCHAR(100)  NOT NULL,
    pickup_address  VARCHAR(500)  NOT NULL,
    drop_address    VARCHAR(500)  NOT NULL,
    weight_kg       NUMERIC(10, 2) NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    auction_id      UUID,
    vendor_id       UUID,
    delivery_charge NUMERIC(12, 2),
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_auction ON orders (auction_id);

-- Event ids already handled, so Kafka redeliveries are applied only once.
CREATE TABLE processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
