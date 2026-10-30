CREATE TABLE vendor (
    id         UUID PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    email      VARCHAR(320) NOT NULL UNIQUE,
    phone      VARCHAR(32),
    status     VARCHAR(16)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_vendor_status ON vendor (status);
