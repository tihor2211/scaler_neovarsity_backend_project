# 3. Class Diagrams

This chapter describes the low-level design: how the code is organised, the domain classes, and the classes behind the three most involved features (checkout, payments and security). The diagrams were drawn from the source code and show the important members only; getters, constructors and framework annotations are left out.

## 3.1 Code organisation

The code is organised **by feature**, not by technical layer. Each package under `com.dashcart` holds everything for one area of the business: its entities, repositories, services, controllers and request/response types.

Table: Packages {#tbl:packages widths=22,78}
| Package | Responsibility |
|---|---|
| `auth` | Users and roles, password hashing, JWT issue and verification, the logout deny-list, admin seeding |
| `store` | Dark stores and the pincodes each one serves |
| `catalog` | Categories, products and the cached per-store listing |
| `inventory` | Per-store stock; the `reservation` sub-package holds the five checkout strategies |
| `cart` | One cart per user, tied to one store |
| `order` | Checkout, the order lifecycle and the reservation-expiry job |
| `payment` | Razorpay orders, signature checks, the webhook |
| `events` | Kafka order events and the notifications inbox |
| `config`, `common` | Security, caching and scheduling configuration; error handling and paging helpers |

Within each package the classes follow the usual layers of a Spring application, shown in @fig:layers. A request passes through the security filter chain, a controller validates the input and calls a service, and the service applies the business rules inside a transaction and uses repositories (Spring Data JPA, or `JdbcTemplate` where precise SQL matters) and integration components.

![Layers of the application](build/diagrams/layers.png){#fig:layers width=5.4}

Organising by feature keeps related code together and makes the dependencies between features visible. For example, `order` uses `cart`, `store`, `inventory` and `events`, while only `payment` and `events` use `order`. The one two-way dependency is between `order` and `events`: the publisher accepts an order entity, and orders call the publisher. A separate event-contract type would remove it. If one area later needs to become a separate service, its package is already the natural boundary.

## 3.2 Domain model

The domain classes are JPA entities mapped onto the tables of Chapter 4. @fig:domain-shopping covers accounts, stores, catalogue, stock and carts; @fig:domain-orders covers orders, payments, webhooks and notifications.

![Domain model: accounts, stores, catalogue, stock and carts](build/diagrams/domain-shopping.png){#fig:domain-shopping maxheight=7}

![Domain model: orders, payments and notifications](build/diagrams/domain-orders.png){#fig:domain-orders maxheight=7}

Three design decisions in the domain model are worth calling out.

**References by id instead of object associations.** An order stores `userId` and `storeId`, not `User` and `Store` objects; an order item stores `orderId` and `productId`. JPA associations (`@ManyToOne`, `@OneToMany`) would make it easy to load large object graphs by accident, to trigger one extra query per row (the "N+1" problem), or to cascade a change further than intended. Referencing other aggregates by identity [5] keeps each entity small, makes every database access explicit in a repository method, and fits the decision to switch off open-session-in-view (`spring.jpa.open-in-view=false`), so no lazy loading can happen while a response is being written. Where a screen needs data from several tables, a repository query joins them and returns a read-only projection. For example, the store listing joins `store_inventory`, `products` and `categories` in one query.

**Composite keys as records.** Stock is identified by (store, product) and a cart line by (cart, product). These keys are Java records (`StoreInventoryId`, `CartItemId`) embedded in the entity, so equality and hashing come for free and the database primary key matches the business identity.

**Behaviour on the entity, rules in the service.** Entities expose only meaningful state changes (`Payment.captured(...)`, `Payment.failed(...)`, `WebhookEvent.processed()`, `Product.update(...)`) rather than a setter for every field. Rules that involve several entities or concurrency, such as reserving stock or moving an order between statuses, live in services and use single SQL statements, as described next.

## 3.3 Checkout and stock reservation

@fig:checkout-classes shows the classes behind checkout. They are an application of the **Strategy** pattern [4]: one interface, `StockReservationStrategy`, with five implementations that make the same promise in different ways.

![Checkout and the stock-reservation strategies](build/diagrams/checkout-classes.png){#fig:checkout-classes}

The interface has two operations, which checkout calls at different points of its transaction:

- `precheck(storeId, items)` runs first and must not lock any stock rows. It lets checkout turn a customer away cheaply when stock has obviously run out.
- `reserve(storeId, items)` runs last, just before the commit, and is the step that actually decides. It either reserves every item or throws `InsufficientStockException` (409 to the customer). The optimistic strategy can also throw `ReservationConflictException`, which tells `CheckoutService` to retry the whole checkout in a new transaction.

`ReservationStrategies` collects every implementation into an `EnumMap` by type and keeps the active one in an `AtomicReference`, so an administrator can switch strategies while the application is running (`PUT /admin/checkout/strategy`). This is what allows the load tests in Chapter 5 to compare all five on the same running instance. `StockPrecheck` is a small shared helper for the database strategies' early check. `RedisStockStore` wraps the two Lua scripts used by the Redis strategy. `StockLedger` handles what happens to reserved stock after checkout, whichever strategy reserved it: it confirms the units as sold when payment arrives, and releases them when an order expires or is cancelled.

## 3.4 Payments and order events

@fig:payment-classes shows the payment classes and how order changes become events.

![Payments, order transitions and events](build/diagrams/payment-classes.png){#fig:payment-classes}

`PaymentService` has three entry points. `start` asks `RazorpayGateway` to create a Razorpay order and records a payment attempt. `verify` checks the signature returned by Razorpay Checkout. `handleWebhook` checks the webhook signature over the raw request body and skips events already recorded. Both confirmation paths end in the same private `capture` method, which does nothing if the payment is already captured, so confirming twice is harmless. `Signatures` is a small utility for HMAC-SHA256 [20] with a constant-time comparison.

Order status changes go through `OrderService`, which delegates to a single repository method, `CustomerOrderRepository.transition(id, from, to, now)`. Every change, whether made by the customer, an admin, a payment or the expiry job, publishes an event through `OrderEventPublisher`. `NotificationListener` consumes the events from Kafka in a separate consumer group.

## 3.5 Security

@fig:security-classes shows the authentication classes.

![Authentication and authorisation classes](build/diagrams/security-classes.png){#fig:security-classes width=5.8}

The API is stateless: there are no server-side sessions, and every request carries a JWT [18] in the `Authorization` header. `JwtService`, built on the jjwt library [21], issues tokens signed with HMAC-SHA256. A token carries the user id (subject), e-mail, role, a unique token id (`jti`) and a one-hour expiry. `JwtAuthenticationFilter` runs once per request before Spring Security's standard filters. It verifies the token, rejects it if its id is on the logout deny-list, and places an `AuthUser` record in the security context, which controllers receive through `@AuthenticationPrincipal`. Logout adds the token id to Redis with a time-to-live equal to the token's remaining lifetime, so the deny-list cleans itself up. `SecurityConfig` declares which paths are public (browsing, register and login, health, the Razorpay webhook) and which need the ADMIN role (`/admin/**`, `/actuator/**` except health).

## 3.6 Order lifecycle

@fig:order-states is the order state machine. Orders in CREATED or PAYMENT_PENDING hold reserved stock. Three things can end that state: a captured payment (the order becomes PAID and the units are sold), a cancellation by the customer, or the expiry job (in both of these cases the units go back on sale).

![Order state machine](build/diagrams/order-states.png){#fig:order-states maxheight=6.5}

The difficulty is that these three can happen at the same instant: a payment can arrive in the same millisecond as the expiry job looks at the order. Each transition is therefore a single conditional `UPDATE` that names the statuses it is allowed to move from (@lst:transition). The database applies the condition and the change atomically, so exactly one actor's update succeeds; the other updates zero rows and backs off. A payment that loses this race against expiry is recorded as REFUND_REQUIRED rather than silently taking money for an order whose stock was already released.

Listing: Conditional status transition (CustomerOrderRepository) {#lst:transition}
```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("""
        update CustomerOrder o set o.status = :to, o.updatedAt = :now
        where o.id = :id and o.status in :from
        """)
int transition(@Param("id") Long id, @Param("from") Collection<OrderStatus> from,
        @Param("to") OrderStatus to, @Param("now") Instant now);
```

## 3.7 Design patterns and principles used

Table: Design patterns in DashCart {#tbl:patterns widths=24,76}
| Pattern / principle | Where and why |
|---|---|
| Strategy [4] | `StockReservationStrategy` and its five implementations; chosen at runtime through `ReservationStrategies`. |
| Repository | Spring Data JPA interfaces for every aggregate; custom queries for projections and conditional updates. |
| Data Transfer Object | Java records for every request and response, validated with Bean Validation; entities never leave the service layer. |
| Optimistic and pessimistic locking [3] | Two of the reservation strategies (version column; `SELECT ... FOR UPDATE`). |
| Compare-and-set [2] | Conditional `UPDATE ... WHERE` for stock (CONDITIONAL_UPDATE strategy) and for every order status transition. |
| Idempotent receiver | Webhook events stored under a unique (provider, event id); notifications unique per (order, status); capture is a no-op when already captured. |
| Publish after commit | Kafka events, cache invalidation and Redis compensation are registered as transaction synchronisations and run only after a commit (or a rollback). |
| Cache-aside | `@Cacheable` listings in Redis with a 30-second expiry and explicit invalidation. |
| Template method | `TransactionTemplate` for programmatic transactions [11] where the retry loop has to sit outside the transaction. |
| Problem details | One error format for the whole API (RFC 9457 [19]), produced by a global exception handler. |

## 3.8 Error handling

Every error leaves the API in the same shape, the RFC 9457 *problem details* format [19], with content type `application/problem+json`. Services throw a small `ApiException` with an HTTP status (400, 401, 404 or 409). `GlobalExceptionHandler` converts it, along with validation failures, into a `ProblemDetail`. Validation errors carry an extra `errors` map from field name to message, so a client can highlight the wrong input. Errors raised inside Spring Security (missing or invalid token, wrong role) happen before any controller runs, so `SecurityConfig` writes the same format itself. @lst:problem shows two real responses: a checkout rejected because stock ran out, and a registration with invalid fields.

Listing: Error responses in problem-details format {#lst:problem}
```json
HTTP/1.1 409   Content-Type: application/problem+json
{"detail": "Not enough Toor Dal left for this order",
 "instance": "/orders/checkout", "status": 409, "title": "Conflict"}

HTTP/1.1 400   Content-Type: application/problem+json
{"detail": "Request validation failed", "instance": "/auth/register",
 "status": 400, "title": "Bad Request",
 "errors": {"email": "must be a well-formed email address",
            "name": "must not be blank",
            "password": "size must be between 8 and 72"}}
```
