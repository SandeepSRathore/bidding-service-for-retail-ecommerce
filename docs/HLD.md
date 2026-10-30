# HLD: Delivery Bidding Service for a Retail E-commerce Company

## Context
The retailer wants delivery vendors to compete on price before it hands off orders. An admin opens a **1-hour bidding window** for a set of orders (example: 2 orders and 10 vendors). When the window opens, **every active vendor gets an email**. During the window, each vendor submits a **sealed bid**: one **price per order** that applies to every order in the window. Vendors can revise their bid until the window closes. When it closes, the vendor with the **lowest price per order wins all the orders in the window**, and the deliveries are assigned to that vendor.

**Decisions already made:**
- Stack: Java 21 + Spring Boot 4.0 (Spring Cloud 2025.1), Kafka, PostgreSQL, Docker Compose
- Bid type: sealed bid (vendors see only their own bid)
- Bid unit: one price per order, applied to all orders in the window
- Trigger: admin API call with a list of order IDs
- Notifications: email only
- Assumption: at least one vendor bids on every window

**Assumptions:**
- **Ties:** if two prices are equal, the bid submitted (or last revised) earliest wins.
- **Window length:** a config property, `auction.window-duration=PT1H`. A demo can shorten it, for example to `PT2M`.
- **Orders in more than one window:** not allowed. An order is locked to one window at a time.

---

## 1. Architecture overview

```
                        ┌──────────────────────┐
  Admin / Vendor ──────▶│  API Gateway         │  (Spring Cloud Gateway, JWT auth, roles ADMIN / VENDOR)
                        └─────────┬────────────┘
          ┌───────────────┬───────┴────────┬──────────────────┐
          ▼               ▼                ▼                  ▼
   ┌────────────┐  ┌────────────┐   ┌──────────────┐   ┌──────────────┐
   │ Vendor Svc │  │ Order Svc  │◀──│ Auction Svc  │   │ Notification │
   │ (vendor_db)│  │ (order_db) │REST│ (auction_db) │   │ Svc (notif_db)│
   └─────┬──────┘  └─────▲──────┘   └──────┬───────┘   └──────▲───────┘
         │               │ consume         │ outbox → publish  │ consume
         │               │                 ▼                   │
         │         ┌─────┴─────────────────────────────────────┴──┐
         │         │                  Kafka                        │
         │         │  auction.opened   auction.awarded             │
         │         └───────────────────────────────────────────────┘
         └──── REST (active vendor list) ◀──── Notification Svc ───▶ SMTP (Mailpit locally)
```

| Service | Responsibility | Store |
|---|---|---|
| **api-gateway** | Routing, JWT validation, role checks, identity headers (`X-User-Id`, `X-User-Role`) | — |
| **vendor-service** | Register and manage delivery vendors (name, email, status ACTIVE/INACTIVE) | `vendor_db` |
| **order-service** | Store orders. Lock orders to a window. Assign orders to the winning vendor when the window closes. | `order_db` |
| **auction-service** (core) | Open windows, accept and revise sealed bids, close windows on time, pick the winner, publish events | `auction_db` |
| **notification-service** | Email vendors when a window opens, email the winner the delivery manifest, email the vendors who lost | `notification_db` |

Each service has its own database (a separate schema or DB on one Postgres instance for local dev). Services do not share tables.

---

## 2. End-to-end flow

```
Admin ─POST /auctions {orderIds:[O1,O2]}─▶ Auction Svc
   Auction Svc ─POST /orders/lock {auctionId, orderIds}─▶ Order Svc   (orders: PENDING → IN_AUCTION; rejects already-locked orders)
   Auction Svc: INSERT auction(status=OPEN, starts_at=now, ends_at=now+1h) + outbox row  [same DB transaction]
   Outbox relay ─▶ Kafka "auction.opened"
Notification Svc ◀─ auction.opened
   ─GET /vendors?status=ACTIVE─▶ Vendor Svc  → send 10 emails ("Bidding open for 2 orders, closes at 14:00 UTC")
Vendor V1..V10 ─PUT /auctions/{id}/bid {pricePerOrder: 120.00}─▶ Auction Svc  (upsert; revisions allowed until ends_at)
── ends_at reached ──
Auction Closer job (inside Auction Svc):
   lock auction row → status CLOSED → pick min(price_per_order), tie → earliest updated_at
   → status AWARDED, winner_vendor_id, winning_price + outbox row → Kafka "auction.awarded"
Order Svc ◀─ auction.awarded → O1, O2: IN_AUCTION → ASSIGNED (vendor_id, delivery_charge)
Notification Svc ◀─ auction.awarded → email the winner the delivery manifest (order details); email the other vendors "not selected"
```

---

## 3. Key design decisions

### 3.1 Closing the window on time
- A **scheduled closer** in auction-service runs every 5 seconds. It looks for `status='OPEN' AND ends_at <= now()`.
- The closer claims each due window with `SELECT … FOR UPDATE SKIP LOCKED`. Several auction-service instances can run the closer at once without closing the same window twice, so no extra lock library (such as ShedLock) is needed.
- The closer can finish up to 5 seconds after `ends_at`. That delay does **not** let late bids in: bid acceptance checks the server's clock directly (`now() < ends_at`). It does not rely on the status changing.
- **Race between the last bid and closing:** a bid write takes a shared lock (`FOR SHARE`) on the auction row and the closer takes an exclusive one (`FOR UPDATE SKIP LOCKED`). Bids don't block each other. A bid still in flight makes the closer skip that window until its next run, so a bid commits before the close or is rejected after it, never in between.
- If auction-service restarts, nothing is lost. Status and `ends_at` are in the database, so the next closer run picks up any overdue windows.

### 3.2 Sealed bids
- `bid` table has `UNIQUE(auction_id, vendor_id)`. `PUT` is an upsert, so each vendor has exactly one current bid. `updated_at` changes on each revision and is used for tie-breaking.
- `GET /auctions/{id}/bid` returns only the caller's own bid. No endpoint shows other vendors' bids until the window is awarded.
- Validation: the price must be greater than 0 with at most 2 decimals. The vendor must be ACTIVE (from the JWT claim, re-checked against a cached vendor list). The auction must be OPEN and `now < ends_at`.

### 3.3 Reliable events (no lost or duplicate side effects)
- **Transactional outbox** in auction-service: the auction state change and the event row are written in one DB transaction. A relay (a scheduled poller) publishes the event to Kafka and marks the row sent.
- **Idempotent consumers:** order-service and notification-service record `event_id` in a `processed_events` table and skip duplicates. Kafka delivers at least once, so duplicates can happen.
- **Retries and a dead-letter topic:** Spring Kafka `DefaultErrorHandler` with exponential backoff. After that, failed events go to `<topic>.DLT`.
- Each email send is logged in `notification_log`, with `UNIQUE(auction_id, vendor_id, type)`, so a retry never sends the same email twice.

### 3.4 Sync vs async calls
- **Synchronous (REST)** only where an answer is needed right away:
  - auction-service calls order-service to lock orders. The admin needs to know at once if an order is invalid or already locked.
  - notification-service calls vendor-service to get the list of vendors to email.
- **Asynchronous (Kafka)** for every result of a state change.
- REST calls use short timeouts (2 s to connect, 5 s to read). Idempotent calls are retried on I/O errors and 5xx responses with Spring Framework 7's built-in `@Retryable`.

---

## 4. Data model (main tables)

**vendor_db.vendor**: `id (UUID), name, email, phone, status, created_at`

**order_db.orders**: `id, customer_ref, pickup_address, drop_address, weight_kg, status (PENDING|IN_AUCTION|ASSIGNED), auction_id, vendor_id, delivery_charge, updated_at`

**auction_db.auction**: `id, status (OPEN|AWARDED|CLOSED), starts_at, ends_at, winner_vendor_id, winning_price_per_order, closed_at, created_by, created_at`. A window goes OPEN → AWARDED, or OPEN → CLOSED if nobody bid; in that case its orders are released back to PENDING.

**auction_db.auction_order**: `auction_id, order_id` (snapshot of the orders in the window)

**auction_db.bid**: `id, auction_id, vendor_id, price_per_order NUMERIC(12,2), created_at, updated_at, UNIQUE(auction_id, vendor_id)`

**auction_db.outbox**: `id, aggregate_id, topic, event_type, payload JSONB, created_at, published_at`

**notification_db.notification_log**: `id, auction_id, vendor_id, type (AUCTION_OPENED|AWARDED|NOT_SELECTED), recipient, sent_at` (one row per email actually sent)

**order_db / notification_db.processed_event**: `event_id, processed_at` (makes the consumers idempotent)

## 5. Events (in a shared `common-events` module)
```
AuctionOpened  { eventId, auctionId, orderIds[], orderCount, startsAt, endsAt }
AuctionAwarded { eventId, auctionId, orderIds[], winnerVendorId, pricePerOrder, totalCharge, participantVendorIds[] }
```

## 6. APIs (through the gateway)
| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/vendors` | ADMIN | Register a vendor |
| GET | `/vendors?status=ACTIVE` | ADMIN / internal | List vendors |
| PATCH | `/vendors/{id}/status` | ADMIN | Activate or deactivate a vendor |
| POST | `/orders` | ADMIN | Create an order (test data) |
| GET | `/orders/{id}`, `/orders?auctionId=` | ADMIN / internal | Order status and assigned vendor |
| POST | `/internal/orders/lock`, `/internal/orders/release` | internal only | Lock orders to a window or release them (never routed by the gateway) |
| POST | `/auctions` | ADMIN | Open a window `{orderIds[]}` → `201 {auctionId, endsAt}` |
| GET | `/auctions?status=OPEN` | ADMIN / VENDOR | List windows by status |
| GET | `/auctions/{id}` | ADMIN / VENDOR | Window details: orders and time left. The winner is shown only after the window is awarded. |
| PUT | `/auctions/{id}/bid` | VENDOR | Submit or revise `{pricePerOrder}` |
| GET | `/auctions/{id}/bid` | VENDOR | View my own bid |
| POST | `/auth/dev-token` | dev only | Issue a test JWT for an admin or a vendor |

---

## 7. Non-functional points
- **Scalability:** all services are stateless and can scale out. The bid path is one indexed upsert. Kafka partitions are keyed by `auctionId`, so each window's events stay in order.
- **Security:** JWT (HS256 for dev, can move to Keycloak or another OIDC provider later). The vendor identity comes from the token, never from the request body.
- **Observability:** Spring Actuator health checks, Micrometer metrics: `auction.bids{result}`, `auction.close.lag`, `auction.closed{outcome}`, and `notification.emails{type,result}`. Correlation-ID propagation is not built yet; Micrometer Tracing is the natural next step.
- **Time:** all times are stored in UTC (`timestamptz`). Every service gets time from an injectable `Clock`, so tests can control it.

## 8. Repository layout (Maven multi-module)
```
bidding-service-for-retail-ecommerce/
├── pom.xml                       (parent; Spring Boot 4.0.9, Java 21)
├── common-events/                (event records, topic names)
├── api-gateway/
├── vendor-service/
├── order-service/
├── auction-service/              (bid API, closer job, outbox relay)
├── notification-service/         (Kafka consumers, JavaMailSender, plain-text emails)
├── docker-compose.yml            (Postgres, Kafka [KRaft], Mailpit, all services)
└── docs/HLD.md                   (this design + diagrams)
```
Flyway handles database migrations for each service.

## 9. Implementation phases (done)
1. Parent POM, `common-events`, docker-compose infrastructure (Postgres, Kafka, Mailpit)
2. vendor-service and order-service (CRUD, order locking, `auction.awarded` consumer)
3. auction-service (open window, sealed-bid upsert, closer, outbox relay)
4. notification-service (opened, awarded, and not-selected emails, with idempotency)
5. api-gateway (routes, JWT, roles), plus `docs/HLD.md`

## 10. Verification
- **Unit tests:** winner selection (lowest price, tie goes to the earliest bid), bid validation (late bid, inactive vendor, price ≤ 0), closer state changes. Time is controlled with a fixed `Clock`.
- **Integration tests (Testcontainers: Postgres + Kafka):** outbox → Kafka → consumer path; a duplicate event is processed only once; a bid sent at the same moment the window closes is either accepted or rejected cleanly.
- **End-to-end demo** (`docker compose up`, `auction.window-duration=PT2M`):
  1. Create 10 vendors and 2 orders.
  2. `POST /auctions` → check Mailpit (http://localhost:8025) for 10 "bidding open" emails.
  3. The 10 vendors submit prices, and one vendor revises its price lower.
  4. Bid after the window closes → `409 AUCTION_CLOSED`.
  5. After closing: `GET /orders/{id}` shows both orders `ASSIGNED` to the lowest bidder with the right charge, and Mailpit has 1 manifest email and 9 "not selected" emails.

## 11. Not built yet
- Rate limiting on the bid endpoints (needs Redis for Spring Cloud Gateway's `RequestRateLimiter`)
- Correlation-ID / distributed tracing
- A real identity provider. `/auth/dev-token` stands in for one. It is off by default and only the local Docker Compose stack enables it; while it is off, tokens it issued are rejected.
