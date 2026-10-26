# DashCart — quick-commerce backend

Backend for a 10-minute grocery delivery app (in the style of Blinkit / Zepto), built as the applied
project for the Scaler Neovarsity – Woolf MSc in Computer Science (Backend Specialization).

A customer enters a pincode, gets served by the nearest dark store, browses that store's stock,
adds items to a cart and checks out. Checkout reserves the stock, takes payment through Razorpay
(test mode) and releases the stock if payment doesn't arrive in 10 minutes.

**Key feature: checkout without overselling.** When many customers buy the last few units at the
same moment, the store must never sell more than it has. The project implements four stock
reservation strategies behind one interface and load-tests them against each other:

| Strategy | Idea |
|---|---|
| Naive | Read stock, check, write. Expected to oversell under concurrency |
| Optimistic lock | Version column; retry on conflict |
| Pessimistic lock | `SELECT … FOR UPDATE` on the stock row |
| Redis atomic | Lua script checks and decrements in one step |

## Tech stack

Java 21 · Spring Boot 4.1 · Spring Security (JWT) · Spring Data JPA / Hibernate · MySQL 8.4 · Flyway ·
Redis 7.4 · Razorpay · Docker · Testcontainers · k6 · AWS (EC2, RDS, VPC)

## Running locally

Needs Java 21 and Docker.

```bash
docker compose up -d                                  # MySQL on 3306, Redis on 6379
export JAVA_HOME=/opt/homebrew/opt/openjdk@21         # or any Java 21
./mvnw spring-boot:run                                # app on http://localhost:8080
curl http://localhost:8080/actuator/health            # should say "UP"
```

Tests start their own MySQL and Redis containers:

```bash
./mvnw verify
```

## Project structure

```
src/main/java/com/dashcart/
  config/          security and app configuration
src/main/resources/
  application.properties
  db/migration/    Flyway SQL migrations (the schema lives here)
docker-compose.yml local MySQL + Redis
```

## Status

- [x] Project skeleton, Docker Compose, database schema (Flyway V1)
- [x] Auth (register / login / logout, JWT with jjwt, Redis deny-list)
- [x] Stores and pincode lookup
- [x] Catalogue and per-store inventory (+ demo data: 3 Bengaluru stores, 30 products)
- [x] Cart (one store per cart, stock check on add, per-item limit)
- [x] Checkout with five stock-reservation strategies (naive, optimistic, pessimistic, conditional update, Redis Lua)
- [ ] Razorpay payment and webhook
- [x] Order lifecycle: cancel, 10-minute reservation expiry job, admin fulfilment steps
- [ ] Redis catalogue cache
- [ ] Load tests (k6) and benchmark results
- [ ] Kafka order events
- [ ] AWS deployment
