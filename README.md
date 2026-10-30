# DashCart — quick-commerce backend

Backend for a 10-minute grocery delivery app (in the style of Blinkit / Zepto), built as the applied
project for the Scaler Neovarsity – Woolf MSc in Computer Science (Backend Specialization).

A customer enters a pincode, gets served by the nearest dark store, browses that store's stock,
adds items to a cart and checks out. Checkout reserves the stock, takes payment through Razorpay
(test mode) and releases the stock if payment doesn't arrive in 10 minutes. Order events go through
Kafka to a notifications inbox.

**Key feature: checkout without overselling.** When many customers buy the last few units at the
same moment, a store must never sell more than it has. Checkout has five interchangeable stock
reservation strategies behind one interface, switchable at runtime, and a k6 load test runs a
flash sale against each:

| Strategy | Idea |
|---|---|
| `NAIVE` | Read stock, check, write. The baseline; oversells under concurrency |
| `OPTIMISTIC` | Version column; retry the whole checkout on conflict |
| `PESSIMISTIC` | `SELECT … FOR UPDATE` on the stock rows |
| `CONDITIONAL_UPDATE` | `UPDATE … SET qty = qty - ? WHERE qty >= ?` (default) |
| `REDIS` | Lua script checks and decrements stock in Redis atomically |

## Results

Flash sale: 500 buyers at once for 100 units of one product (median of 3 runs, Apple M3 laptop).
Full numbers and raw k6 output in [`load-tests/results`](load-tests/results).

| Strategy | Units sold | Median ms | p95 ms | Everyone served in ms |
|---|---|---|---|---|
| NAIVE | **500 (oversold 400)** | 797 | 1087 | 1149 |
| OPTIMISTIC | 100 | 1193 | 1309 | 1335 |
| PESSIMISTIC | 100 | 394 | 485 | 519 |
| CONDITIONAL_UPDATE | 100 | **340** | **462** | **494** |
| REDIS | 100 | 419 | 534 | 576 |

Taking the stock lock last in the checkout transaction ("lock late") cut the median of the locking
strategies by 11–17% compared with the first version ([before](load-tests/results/rush-1-baseline/summary.md),
[after](load-tests/results/rush-2-lock-late/summary.md)).

Caching product listings in Redis: 1,743 → 12,011 requests/s, median 54.9 → 6.7 ms
(100 virtual users, [results](load-tests/results/cache-off-vs-on/summary.md)).

## Tech stack

Java 21 · Spring Boot 4.1 · Spring Security (JWT with jjwt) · Spring Data JPA / Hibernate ·
MySQL 8.4 · Flyway · Redis 7.4 · Kafka 4 · Razorpay · Docker · Testcontainers · k6 · AWS

## Running locally

Needs Docker. Optional: copy `.env.example` to `.env` to set an admin login and Razorpay test keys.

**Everything in Docker:**

```bash
docker compose --profile app up -d --build            # MySQL, Redis, Kafka and the API
curl http://localhost:8080/actuator/health            # "UP"
```

**API from the IDE / Maven** (needs Java 21):

```bash
docker compose up -d                                  # MySQL 3306, Redis 6379, Kafka 9092
ADMIN_EMAIL=admin@dashcart.local ADMIN_PASSWORD=change-me ./mvnw spring-boot:run
```

**Tests:** 76 in total. 39 integration tests start their own MySQL, Redis and Kafka containers,
including a concurrency test that runs 40 buyers against 15 units with every strategy. 37 unit tests
need no containers. `verify` also writes a JaCoCo coverage report (90.5% of lines, 72.8% of branches)
and fails the build if coverage drops below 85% of lines or 65% of branches:

```bash
./mvnw verify                      # report: target/site/jacoco/index.html
```

**Load tests** need [k6](https://k6.io): see the scripts in [`load-tests`](load-tests).

Without Razorpay keys the app uses a fake gateway, so the whole flow works offline. With test keys,
open `http://localhost:8080/pay.html` to pay for an order in Razorpay's test checkout.

## API

| Area | Endpoints |
|---|---|
| Auth | `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `GET /users/me` |
| Stores | `GET /stores/serviceable?pincode=` · admin: `POST/GET /admin/stores`, `PUT /admin/stores/{id}/pincodes`, `PUT /admin/stores/{id}/active` |
| Catalogue | `GET /categories`, `GET /stores/{id}/products`, `GET /stores/{id}/products/{productId}` · admin: `POST /admin/categories`, `POST /admin/products`, `PUT /admin/products/{id}` |
| Inventory | admin: `GET/PUT /admin/stores/{id}/inventory` |
| Cart | `GET /cart`, `PUT /cart/items/{productId}`, `DELETE /cart` |
| Orders | `POST /orders/checkout`, `GET /orders`, `GET /orders/{id}`, `POST /orders/{id}/cancel` · admin: `POST /admin/orders/{id}/advance`, `POST /admin/orders/expire-due` |
| Payments | `POST /orders/{id}/payment`, `GET /orders/{id}/payments`, `POST /payments/razorpay/verify`, `POST /payments/razorpay/webhook` |
| Notifications | `GET /notifications` |
| Operations | admin: `GET/PUT /admin/checkout/strategy`, `/actuator/metrics` · public: `/actuator/health` |

Errors are returned as RFC 9457 problem details (`application/problem+json`).

## Project structure

```
src/main/java/com/dashcart/
  auth/            users, JWT issue/verify, logout deny-list in Redis
  store/           dark stores and the pincodes they serve
  catalog/         categories, products, cached store listings
  inventory/       per-store stock; reservation/ holds the five checkout strategies
  cart/            one cart per user, tied to one store
  order/           checkout, order lifecycle, reservation expiry job
  payment/         Razorpay orders, signature checks, webhook
  events/          Kafka order events and the notifications inbox
  config/, common/ security, caching, error handling
src/main/resources/db/migration/   Flyway SQL migrations (the schema lives here)
load-tests/        k6 scripts, benchmark runners and committed results
report/            the applied project report: Markdown chapters, PlantUML diagrams, build scripts
Dockerfile, docker-compose.yml
```

## Status

- [x] Auth, stores, catalogue, inventory, cart
- [x] Checkout with five stock-reservation strategies, lock-late optimisation
- [x] Razorpay payments (verify + webhook, idempotent, late payments flagged for refund)
- [x] Order lifecycle: cancel, 10-minute reservation expiry, fulfilment steps
- [x] Redis catalogue cache, Kafka order events and notifications
- [x] Load tests and results
- [x] Dockerfile and full-stack Docker Compose
- [ ] AWS deployment
