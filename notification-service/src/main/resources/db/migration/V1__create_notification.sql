-- One row per email actually sent. The unique key stops a retried event from emailing a vendor twice.
CREATE TABLE notification_log (
    id         UUID PRIMARY KEY,
    auction_id UUID         NOT NULL,
    vendor_id  UUID         NOT NULL,
    type       VARCHAR(32)  NOT NULL,
    recipient  VARCHAR(320) NOT NULL,
    sent_at    TIMESTAMPTZ  NOT NULL,
    UNIQUE (auction_id, vendor_id, type)
);

CREATE TABLE processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
