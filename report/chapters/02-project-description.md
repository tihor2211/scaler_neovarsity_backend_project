# 1. Project Description

## 1.1 Background: quick commerce

Quick commerce is online grocery and essentials delivery promised within ten to twenty minutes. In India it is the model behind services such as Blinkit, Zepto and Swiggy Instamart. The speed doesn't come from faster riders but from where the stock sits. Instead of shipping from a large central warehouse, each service runs many small **dark stores**: compact warehouses, closed to walk-in shoppers, placed inside the neighbourhoods they serve. Each dark store covers a handful of pincodes, holds a few thousand fast-moving products and keeps only a small quantity of each.

This shapes the backend in three ways. First, the catalogue a customer sees depends on where they are: the same app shows a different list, with different stock levels, in Koramangala than in Indiranagar. Second, stock is small and changes constantly, so "only 2 left" is a normal state, not an edge case. Third, many customers read the same data (listings) at once, and occasionally many try to buy the same last few units at once, such as when a discount goes live or a popular product is restocked.

## 1.2 Problem statement

A backend for this model has to get a few things right that a simple online-shop tutorial usually skips:

1. **No overselling under concurrency.** When 500 people press "Place order" for the last 100 units within the same second, the store must accept at most 100 orders. A check such as "is the stock greater than zero?" followed by a separate update is not enough: two requests can pass the check before either has written its update. This is the classic *lost update* [2].
2. **Stock held by unpaid orders must come back.** Reserving stock at checkout protects paying customers, but a customer who abandons the payment page would otherwise hold units forever. Reservations need a deadline and a reliable release.
3. **Payments must be confirmed exactly once.** Razorpay can report the same payment twice, once through the customer's browser and once through a server-to-server webhook, and webhooks can be redelivered. A payment can also arrive just after its order expired and its stock went back on sale.
4. **Browsing is read-heavy and must stay fast.** Most requests are customers looking at listings, and those must not slow down checkout.

## 1.3 Objectives

The objectives of the project were to:

1. Build a working quick-commerce backend covering accounts, stores and pincode serviceability, a per-store catalogue and inventory, carts, checkout, payment and the order lifecycle.
2. Make checkout safe under heavy concurrency, so that a store never sells more than it has, and show this with automated tests.
3. Implement several well-known concurrency-control techniques behind one interface, measure them under a realistic flash-sale load, and choose a default based on evidence rather than habit.
4. Improve a measured performance metric and report real before-and-after numbers.
5. Integrate a real payment gateway (Razorpay, test mode) with signature verification and idempotent handling of repeated notifications.
6. Decouple side effects such as customer notifications from the request path using an event stream (Kafka).
7. Package the system for the cloud with Docker and deploy it on AWS.

## 1.4 Scope

@tbl:scope summarises what the project covers and what it deliberately leaves out. The excluded items are real parts of a quick-commerce business, but they don't change the backend problems this project studies.

Table: Project scope {#tbl:scope widths=50,50}
| In scope | Out of scope |
|---|---|
| Customer registration, login and logout (JWT) | Customer and rider mobile apps (the API is the product) |
| Dark stores, serviceable pincodes, store on/off switch | Choosing a store by live distance or rider availability |
| Categories, products, per-store stock, listing search | Full-text search engine, recommendations |
| Cart (one store per cart) and checkout | Coupons, delivery fees, taxes |
| Five stock-reservation strategies, switchable at runtime | Multi-warehouse fulfilment, stock transfers |
| Razorpay payments (test mode): verify and webhook | Refund execution (refunds are flagged, not issued) |
| Order lifecycle: expiry, cancel, fulfilment steps | Rider assignment and live tracking |
| Kafka order events and a notifications inbox | SMS, e-mail and push delivery |
| Load tests, metrics, Docker, AWS deployment | Multi-region deployment, autoscaling policies |

## 1.5 System overview

DashCart is a single Spring Boot application, a *modular monolith*: one deployable unit, organised internally into feature packages (accounts, stores, catalogue, inventory, cart, orders, payments, events) that each have their own controllers, services and repositories. @fig:architecture shows the runtime view.

![DashCart runtime architecture](build/diagrams/architecture.png){#fig:architecture}

Customers and store administrators call the same REST API. Every request first passes through Spring Security, where a filter validates the JSON Web Token (JWT) sent with it. Controllers validate the request body and hand over to services, which hold the business rules and the transaction boundaries.

MySQL is the system of record for everything: users, stores, products, stock, carts, orders and payments. Redis plays three supporting roles. It caches product listings for 30 seconds, remembers logged-out tokens until they expire, and holds stock counters for the Redis reservation strategy. Kafka carries order events from the services that change orders to a consumer that writes customer notifications. Razorpay is the external payment gateway. The API creates a Razorpay order for each payment attempt, and Razorpay calls back with signed webhooks.

@fig:journey follows one customer through the system. The checkout step, where stock is reserved for every item or for none, is the key feature this report examines in Chapter 5.

![Customer journey from pincode to delivery](build/diagrams/customer-journey.png){#fig:journey maxheight=7.2}

## 1.6 How the project was built

The project was developed iteratively. Each iteration added one capability end to end, from the database migration and the API down to the integration tests, and was committed separately. @tbl:iterations lists the iterations in order. The commit history of the repository records the same steps.

Table: Development iterations {#tbl:iterations widths=8,32,60}
| # | Iteration | What it added |
|---|---|---|
| 1 | Skeleton | Spring Boot 4.1 project, Docker Compose for MySQL and Redis, first Flyway schema |
| 2 | Accounts | Register, login and logout with JWT (jjwt) and a Redis deny-list for logged-out tokens |
| 3 | Stores and catalogue | Dark stores, pincodes, categories, products, per-store stock, demo data for three Bengaluru stores |
| 4 | Cart | One cart per user tied to one store, totals and savings, stock and per-item limits |
| 5 | Checkout | Five stock-reservation strategies behind one interface, concurrency test |
| 6 | Payments and first benchmark | Razorpay orders, signature-checked verify and webhook, k6 flash-sale benchmark |
| 7 | Lock late | Checkout reordered so the stock lock is taken last; benchmarked against iteration 6 |
| 8 | Cache and events | Redis listing cache with after-commit invalidation; Kafka order events and notifications |
| 9 | Cache benchmark | Listing load test with the cache off and on |
| 10 | Packaging | Dockerfile, full-stack Docker Compose, AWS deployment |

Three practices ran through every iteration. **Tests against real infrastructure:** the integration tests start real MySQL, Redis and Kafka containers with Testcontainers [30], so they exercise the same SQL, locking and serialisation as production. **Measure before optimising:** every performance claim in this report comes from a load test whose raw output is committed in the repository (`load-tests/results`). **Schema as code:** the database schema exists only as versioned Flyway migrations [29].

**Use of AI assistance.** I used Claude Code (Anthropic) [1] as an AI pair programmer throughout this project. Working from my choice of problem, scope and technologies, it generated most of the implementation, tests and diagrams and a first draft of this report. I directed the work, reviewed it, and am responsible for this submission.

## 1.7 Relevance

The checkout problem studied here is not specific to groceries. Whenever many people compete for a limited, countable resource, the same failure appears if concurrency is handled carelessly: concert tickets, train and flight seats, limited-edition product drops, flash-sale coupons and hospital appointment slots. The results in Chapter 5 show how large the difference is in practice. A plausible-looking implementation sold five times the stock it had, while four correct alternatives differed from each other by a factor of three in checkout time under the same load. The other techniques in the project are equally general: payment-webhook idempotency, reservations with expiry, after-commit cache invalidation and events published only after a commit.
