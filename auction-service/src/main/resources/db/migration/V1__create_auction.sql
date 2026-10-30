CREATE TABLE auction (
    id                      UUID PRIMARY KEY,
    status                  VARCHAR(16)  NOT NULL,
    starts_at               TIMESTAMPTZ  NOT NULL,
    ends_at                 TIMESTAMPTZ  NOT NULL,
    winner_vendor_id        UUID,
    winning_price_per_order NUMERIC(12, 2),
    closed_at               TIMESTAMPTZ,
    created_by              VARCHAR(100) NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL
);

-- The closer only ever scans open windows by deadline.
CREATE INDEX idx_auction_open_ends_at ON auction (ends_at) WHERE status = 'OPEN';

-- Snapshot of the orders covered by a window.
CREATE TABLE auction_order (
    auction_id UUID NOT NULL REFERENCES auction (id),
    order_id   UUID NOT NULL,
    PRIMARY KEY (auction_id, order_id)
);

-- Sealed bids: one current bid per vendor per window, revised in place.
CREATE TABLE bid (
    id              UUID PRIMARY KEY,
    auction_id      UUID           NOT NULL REFERENCES auction (id),
    vendor_id       UUID           NOT NULL,
    price_per_order NUMERIC(12, 2) NOT NULL CHECK (price_per_order > 0),
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,
    UNIQUE (auction_id, vendor_id)
);

-- Transactional outbox: events are written with the state change and relayed to Kafka afterwards.
CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    aggregate_id UUID         NOT NULL,
    topic        VARCHAR(100) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
