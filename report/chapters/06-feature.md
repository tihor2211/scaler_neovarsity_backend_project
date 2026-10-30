# 5. Feature Development Process

The key feature chosen for this chapter is **checkout without overselling**: turning a cart into an order while reserving its stock, so that a store never sells more units than it has, even when hundreds of customers check out at the same moment. The chapter explains the problem, follows a checkout request through the code, compares five ways of reserving stock, and then reports two measured optimisations: reordering the checkout transaction ("lock late") and caching product listings in Redis.

## 5.1 The problem: lost updates

The obvious way to reserve stock is to read the current quantity, check it in Java, and write the new value:

```sql
SELECT available_qty FROM store_inventory
 WHERE store_id = ? AND product_id = ?;                       -- returns 1
-- Java: if (1 >= quantity) ...
UPDATE store_inventory SET available_qty = 0, reserved_qty = reserved_qty + 1
 WHERE store_id = ? AND product_id = ?;
```

Each statement is correct on its own, but together they are not atomic. @fig:lost-update shows two checkouts for the last unit interleaving. Both read one unit, both pass the check, and both write zero. MySQL's row lock makes the second `UPDATE` wait for the first transaction to commit, but it doesn't make the second checkout re-read the stock: it simply writes the value it computed earlier. Two orders now hold the same single unit. This is the *lost update* anomaly [2], and MySQL's default isolation level, REPEATABLE READ, does not prevent it for this pattern, because the plain `SELECT` is a non-locking consistent read [6].

![Lost update: two naive checkouts sell the same unit](build/diagrams/lost-update.png){#fig:lost-update width=5.2}

There are two families of fixes. Either make the read and the write a single atomic step, so the check happens on the latest committed value (@fig:conditional-update), or keep them separate and prevent others from changing the row in between (locking), or detect that they did (versioning). The project implements both families, and a fifth option that moves the decision into Redis, and measures all of them under the same load.

![Conditional UPDATE: the second checkout re-checks after the lock is released](build/diagrams/conditional-update.png){#fig:conditional-update width=5.6}

## 5.2 Request flow through the MVC layers

A customer checks out by sending their delivery address and pincode; the items come from their saved cart. @lst:checkout-api shows a real request and response from the running application, and @fig:checkout-sequence shows the request's path through the layers.

Listing: Checkout request and response {#lst:checkout-api}
```json
POST /orders/checkout
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json

{"deliveryAddress": "Flat 12, 5th Cross, Koramangala 4th Block",
 "deliveryPincode": "560034"}

HTTP/1.1 201 Created
{"id": 8308, "status": "CREATED", "storeId": 2, "totalAmount": 368.00,
 "deliveryAddress": "Flat 12, 5th Cross, Koramangala 4th Block",
 "deliveryPincode": "560034", "reservationStrategy": "CONDITIONAL_UPDATE",
 "expiresAt": "2026-10-26T18:06:53.246887Z",
 "createdAt": "2026-10-26T17:56:53.247269Z",
 "items": [
   {"productId": 15, "name": "Toor Dal", "unit": "1 kg", "quantity": 2,
    "unitPrice": 159.00, "lineTotal": 318.00},
   {"productId": 9, "name": "Brown Bread", "unit": "400 g", "quantity": 1,
    "unitPrice": 50.00, "lineTotal": 50.00}]}
```

![Checkout request flow (successful and rejected paths)](build/diagrams/checkout-sequence.png){#fig:checkout-sequence}

1. **Security filter.** `JwtAuthenticationFilter` verifies the token's signature and expiry and checks the Redis deny-list. A missing or invalid token leaves the request anonymous, and Spring Security answers 401 because `/orders/**` requires a login.
2. **Controller.** `OrderController.checkout` is mapped to `POST /orders/checkout`. It receives the logged-in user through `@AuthenticationPrincipal`, and its `@Valid` annotation applies the request record's constraints (address not blank, pincode six digits, first digit not zero) before any business code runs. It returns the service's result with status 201 Created.
3. **Service.** `CheckoutService.checkout` picks the active strategy and runs `placeOrder` inside a `TransactionTemplate`. Using a programmatic transaction instead of `@Transactional` lets the method wrap the transaction in a retry loop for the optimistic strategy: each attempt must be a fresh transaction, because retrying inside the same one would re-read the same MySQL snapshot. The service also records a Micrometer timer tagged with the strategy and the outcome.
4. **Inside the transaction** (@lst:place-order): load the cart and its lines, confirm the pincode is served by the cart's store and that the store and products are active, run the strategy's precheck, insert the order and its items, delete the cart items, and finally call the strategy's `reserve`. Items are sorted by product id first (Section 5.5).
5. **After commit.** The order-created event is handed to Kafka only once the transaction has committed, through a transaction synchronisation, so no consumer ever hears about an order that was rolled back. The response is then built by reading the order back.

Listing: The checkout transaction (CheckoutService, abridged) {#lst:place-order}
```java
private Long placeOrder(Long userId, CheckoutRequest request,
        StockReservationStrategy strategy) {
    Cart cart = carts.findByUserId(userId)
            .orElseThrow(() -> ApiException.badRequest("Your cart is empty"));
    List<CartLine> lines = cartItems.findLines(cart.getId());
    // ... pincode served by the cart's store? store active? products still sold?
    List<StockRequest> items = lines.stream()
            .map(l -> new StockRequest(l.productId(), l.quantity(), l.name()))
            .sorted(Comparator.comparing(StockRequest::productId)) // one lock order
            .toList();

    strategy.precheck(storeId, items);        // early rejection, no row locks

    CustomerOrder order = orders.save(new CustomerOrder(userId, storeId, total,
            address, pincode, strategy.type(), Instant.now().plus(reservationTtl)));
    orderItems.saveAllAndFlush(/* one OrderItem per line, at today's price */);
    cartItems.deleteAllInCart(cart.getId());

    strategy.reserve(storeId, items);         // the deciding step: locks taken last
    events.publish(order, OrderStatus.CREATED); // sent to Kafka after commit
    return order.getId();
}
```

If `reserve` throws because an item has run out, the exception propagates out of the `TransactionTemplate`, which rolls the whole transaction back: the order, its items and the cart deletion disappear together, and the customer gets 409 with their cart intact. There is no partial order and no reservation without an order.

## 5.3 Five reservation strategies

All five strategies implement the same two-step interface (Section 3.3) and all run inside the checkout transaction. @lst:strategies shows the statement each one uses to decide; @tbl:strategies compares them.

Listing: The deciding statements of the five strategies {#lst:strategies}
```sql
-- NAIVE: read, check in Java, write the computed value (unsafe)
SELECT available_qty FROM store_inventory WHERE store_id = ? AND product_id = ?;
UPDATE store_inventory
   SET available_qty = :computed, reserved_qty = reserved_qty + ?,
       version = version + 1
 WHERE store_id = ? AND product_id = ?;

-- OPTIMISTIC: write only if nobody changed the row since we read it
SELECT available_qty, version FROM store_inventory
 WHERE store_id = ? AND product_id = ?;
UPDATE store_inventory
   SET available_qty = :computed, reserved_qty = reserved_qty + ?,
       version = version + 1
 WHERE store_id = ? AND product_id = ? AND version = :readVersion;
-- 0 rows -> ReservationConflictException -> retry the whole checkout (max 5 attempts)

-- PESSIMISTIC: lock the row while reading it
SELECT available_qty FROM store_inventory
 WHERE store_id = ? AND product_id = ? FOR UPDATE;
UPDATE store_inventory
   SET available_qty = available_qty - ?, reserved_qty = reserved_qty + ?,
       version = version + 1
 WHERE store_id = ? AND product_id = ?;

-- CONDITIONAL_UPDATE: check and decrement in one statement
UPDATE store_inventory
   SET available_qty = available_qty - ?, reserved_qty = reserved_qty + ?,
       version = version + 1
 WHERE store_id = ? AND product_id = ? AND available_qty >= ?;
-- 0 rows -> InsufficientStockException

-- REDIS: an atomic Lua script in Redis decides (precheck); the conditional
-- UPDATE above runs as a safety net in reserve
```

**Naive** is kept on purpose as the baseline that demonstrates the bug.

**Optimistic locking** [3] takes no lock while reading and makes the write conditional on the row's `version` being unchanged. If another checkout got there first, zero rows match, and the strategy throws a conflict exception. `CheckoutService` then waits a short, random, growing interval (5–15 ms times the attempt number, so retrying buyers don't collide again in step) and retries in a new transaction, up to five attempts in total.

**Pessimistic locking** [3] uses `SELECT ... FOR UPDATE`, a locking read: it returns the newest committed value and stops any other transaction from changing that row until this one ends [6]. Other checkouts for the same product wait at that statement until the lock holder commits.

**Conditional update** puts the check inside the `UPDATE`'s `WHERE` clause. InnoDB evaluates the condition on the latest committed version of the row while holding the row lock [7], so the check and the decrement can't be separated. It needs one round trip per item instead of two.

**Redis** moves the decision out of MySQL (@fig:redis-strategy). Each store's stock is mirrored in Redis keys such as `stock:{2}:15`. In `precheck`, a Lua script (@lst:lua) checks every item of the order and, only if all have enough, decrements them all. Redis runs a script without interleaving other commands [9], so the check and the decrements are atomic. Buyers who lose are turned away in memory, without touching MySQL's stock rows. Winners go on to write their order, and `reserve` then runs the conditional `UPDATE` as a safety net, so the database still has the final say. Because Redis is not part of the MySQL transaction, the strategy compensates: if the checkout rolls back, a registered after-completion hook gives the units back to Redis. If MySQL ever disagrees with Redis (zero rows updated), the Redis key is deleted after the rollback so the next checkout reloads it from the database.

![The Redis strategy: decide in Redis, confirm in MySQL, compensate on rollback](build/diagrams/redis-strategy.png){#fig:redis-strategy width=5.6}

Listing: Redis reservation script (RedisStockStore) {#lst:lua}
```lua
-- KEYS: stock:{store}:product for each item, ARGV: the quantities
for i = 1, #KEYS do
    local stock = redis.call('GET', KEYS[i])
    if not stock then return -i end              -- missing: caller loads it, retries
    if tonumber(stock) < tonumber(ARGV[i]) then
        return i                                 -- item i is short: reserve nothing
    end
end
for i = 1, #KEYS do
    redis.call('DECRBY', KEYS[i], ARGV[i])
end
return 0
```

Table: Comparison of the reservation strategies {#tbl:strategies widths=27,21,12,24,16 size=9.5}
| Strategy | Where the decision is made | Never oversells? | Cost when buyers collide | Round trips per item |
|---|---|---|---|---|
| NAIVE | Java, on a stale read | No | None, which is the bug | 2 |
| OPTIMISTIC | MySQL version check | Yes | The losing transaction rolls back and retries from the start | 2 per attempt |
| PESSIMISTIC | MySQL row lock | Yes | Buyers queue on the lock | 2 |
| CONDITIONAL_UPDATE | MySQL, inside one UPDATE | Yes | Buyers queue on the row lock | 1 |
| REDIS | Redis Lua script; MySQL confirms | Yes | Losers rejected in memory; winners queue on the row lock | 1 Redis + 1 MySQL |

## 5.4 Lock late

The order of statements inside the checkout transaction matters as much as the choice of strategy. In the first version of checkout, stock was reserved first, straight after validation, and the order, its items and the cart deletion were written afterwards. A row lock taken by `FOR UPDATE` or by an `UPDATE` is held until the transaction ends [7]. So the lock on a popular product's stock row was held across four further statements and the commit, and every other buyer of that product waited for all of them.

The current version takes the lock **last** (@fig:lock-late-timing). Each strategy got a second, non-locking method, `precheck`, which runs first: a plain read for the database strategies, which rejects buyers early once stock is clearly gone, or the Lua script for Redis. The order rows are written next, and the deciding, locking statement runs at the very end, immediately before the commit. The lock is now held for one statement plus the commit. Correctness doesn't depend on the precheck: a plain read can be stale, so passing it proves nothing, and `reserve` still decides. Failing it is safe, though, because during a sell-out stock that is gone doesn't come back.

![Row-lock hold time before and after the lock-late change](build/diagrams/lock-late.png){#fig:lock-late-timing}

The measured effect is reported in Section 5.9.

## 5.5 Avoiding deadlocks

An order can contain several products, so a checkout may lock several stock rows. If one checkout locked product 9 and then 15 while another locked 15 and then 9, each could end up waiting for the other forever. InnoDB would detect the deadlock and abort one of them [8], but only after the damage to latency is done. DashCart prevents it by always locking rows in the same order: checkout sorts the items by product id before calling the strategy, and `StockLedger` sorts them the same way when it confirms or releases stock. With a single global order, a cycle of waits can't form.

## 5.6 After checkout: payment, expiry and cancellation

A reservation is a promise that must be settled one way or the other. @fig:payment-sequence shows the payment path. Starting a payment creates a Razorpay order through Razorpay's Orders API [22], with the amount in paise and HTTP Basic authentication using the key id and secret. This call to an external service is made **outside** any database transaction, so a slow gateway can never hold database locks. The customer then pays in Razorpay Checkout, which returns a payment id and a signature.

![Payment confirmation by browser callback and webhook](build/diagrams/payment-sequence.png){#fig:payment-sequence}

Two paths can confirm the payment, and both must be safe to run in either order and more than once:

- **Browser verification.** The app forwards the ids and signature. The server recomputes HMAC-SHA256 over `order_id|payment_id` with the key secret, using its own stored Razorpay order id rather than the one sent by the client, as Razorpay's integration guide requires [23]. It compares the two signatures in constant time.
- **Webhook.** Razorpay calls `POST /payments/razorpay/webhook` with an `X-Razorpay-Signature` header computed over the raw request body with a separate webhook secret [25]. The controller therefore takes the body as raw bytes and doesn't let JSON parsing change it before the check. Razorpay may deliver the same event more than once and identifies each event with an `x-razorpay-event-id` header [24]. DashCart stores every event under a unique (provider, event id) key and skips any it has already seen.

Both paths lock the payment row (`SELECT ... FOR UPDATE`) and call the same `capture` method. It does nothing if the payment is already captured. Otherwise it moves the order to PAID with a conditional transition (@lst:transition) and turns the reserved units into sold ones (`reserved_qty` goes down).

Unpaid orders are handled by `ReservationExpiryJob`, which runs every 30 seconds. It finds orders in CREATED or PAYMENT_PENDING whose deadline has passed, using the `(status, expires_at)` index, and expires each one in its own transaction: a conditional transition to EXPIRED, then the release of its stock. Customer cancellation uses the same release path. Because every transition is conditional, the job, a cancellation and a payment can race on the same order and exactly one of them wins. If a payment loses to expiry, the money has arrived for an order whose stock is back on sale, so the payment is recorded as REFUND_REQUIRED. The job is also safe to run on several application instances at once, for the same reason.

## 5.7 Testing

The project has two kinds of automated tests. **Integration tests** start the whole application against real MySQL 8.4, Redis 7.4 and Kafka 4 containers created by Testcontainers [30], wired in with Spring Boot's `@ServiceConnection` [16], and drive it through its HTTP API with MockMvc. This choice matters for this feature in particular: locking behaviour, `FOR UPDATE`, conditional updates and Lua scripts can only be tested against the real engines, and an in-memory database or mocks would pass tests that production would fail. **Unit tests** check individual classes with no Spring context and no containers. Collaborators are replaced with Mockito mocks where needed, so these tests run in milliseconds and pin down decisions that are hard to provoke end to end: the optimistic retry limit, the exact order of statements in the checkout transaction, and every branch of the webhook handler. @tbl:tests lists both.

Table: Automated test suites {#tbl:tests widths=35,7,58 size=10}
| Test class | Tests | What it proves |
|---|---|---|
| *Integration tests (real MySQL, Redis, Kafka)* | | |
| AuthFlowIntegrationTest | 6 | Register, login, profile, logout invalidates the token; duplicate e-mail; wrong password; field-level validation errors; admin-only paths |
| BrowseIntegrationTest | 6 | Pincode lookup without login; invalid and unserved pincodes; listing filters; a stock change clears the cached listing; price above MRP rejected |
| CartIntegrationTest | 5 | Add, update and remove with totals; stock and per-item limits; one store per cart; login required |
| CheckoutIntegrationTest | 7 | Checkout reserves stock and empties the cart; cancel releases stock exactly once; expiry releases stock; insufficient stock; pincode mismatch; empty cart; Redis and MySQL stay in step |
| CheckoutConcurrencyTest | 5 | 40 buyers rush 15 units under each strategy; every request gets 201 or 409; the four safe strategies never oversell and available + reserved still equals the stock |
| PaymentIntegrationTest | 7 | Razorpay order for the right amount; verified payment sells the stock; forged signatures rejected; repeated webhook changes nothing; payment after expiry flagged for refund; can't pay another customer's order |
| NotificationIntegrationTest | 2 | Placing and cancelling an order produce two notifications through Kafka; every status has a message |
| DashcartApplicationTests | 1 | The application context starts |
| *Unit tests (no containers)* | | |
| CheckoutServiceTest | 6 | Optimistic conflicts retried in a new transaction, 409 after five attempts; other strategies never retry; out of stock is not retried; a wrong pincode is rejected before any stock work; precheck, order rows, cart delete, reserve and event happen in that order, with items in product-id order |
| PaymentWebhookTest | 8 | Forged signature rejected before anything is read; missing event id and malformed body rejected; capture pays the order; a repeated event id changes nothing; an already captured payment is left alone; late payment flagged for refund; failed payment recorded |
| RazorpayGatewayTest | 2 | The Orders API call (path, Basic authentication, amount in paise) against a local stand-in server; fake order ids without keys |
| JwtServiceTest | 7 | Token round trip; HS256 whatever the key length; unique token ids; forged payload, wrong secret, expired and malformed tokens rejected |
| SignaturesTest | 4 | The published HMAC-SHA256 example value; only the exact body and secret match; Razorpay's order_id\|payment_id format |
| OrderStatusTest | 7 | Fulfilment steps; which statuses hold stock |
| StockKeysAndOrderingTest | 3 | Redis keys use the store as a cluster hash tag; lock order by product id; out-of-stock message |

All 76 tests pass: 39 integration tests and 37 unit tests. The concurrency test starts 40 threads that wait on a shared latch and are released at the same instant. For the four safe strategies it asserts that no more than 15 units were sold and that available plus reserved stock still adds up to 15. For NAIVE it only records what happened; in one run all 40 buyers got an order for the 15 units. The tests also caught three real bugs during development:

1. **A payment status that was never saved.** The order transition is a bulk JPQL `UPDATE` with `clearAutomatically = true`, which empties the persistence context so later reads see the new status. That also detached the `Payment` entity loaded earlier in the same transaction, so its change to CAPTURED was silently dropped. The payment test asserted the status in the database, failed, and the fix was an explicit save.
2. **A cache that was cleared too late.** This is described in Section 5.10.
3. **Tokens signed with the wrong algorithm.** jjwt chooses the HMAC algorithm from the length of the key, so the 384-bit secret recommended for production would have produced HS384 tokens, not the HS256 this report describes. A unit test that decodes the token header failed with a long key; the fix names HS256 explicitly when signing.

**Code coverage.** JaCoCo measures which lines and branches the whole suite executes. @tbl:coverage shows the result. The build is set to fail if line coverage drops below 85% or branch coverage below 65%, so coverage can't quietly erode. Most of the remaining gaps are defensive branches, such as a Redis key that disappears in the middle of a reservation, and simple getters.

Table: Code coverage of the test suite (JaCoCo) {#tbl:coverage widths=40,30,30}
| Measure | Covered | Coverage |
|---|---|---|
| Lines | 1,015 of 1,121 | 90.5% |
| Instructions | 5,269 of 5,787 | 91.0% |
| Methods | 383 of 424 | 90.3% |
| Branches | 174 of 239 | 72.8% |

## 5.8 Benchmark design

The test suite proves correctness on a small scale. The load tests measure correctness and speed under a realistic flash sale. They use k6 [31], an open-source load-testing tool by Grafana Labs, whose scripts are written in JavaScript.

The **flash-sale rush** (`load-tests/checkout-rush.js`) works as follows. During setup it creates a new product with exactly 100 units in one store, registers 500 customers, logs them in and puts one unit in each customer's cart. Setup is not measured. Then 500 virtual users each send one checkout request at the same moment. Afterwards the script reads the stock back and records how many orders were created (201), how many were rejected (409), any other failures, how many units were oversold, and the distribution of response times. The script switches the active strategy through the admin API first, so all strategies run against the same running application. Every strategy was run three times, and the tables report the median of the three runs.

The **listing benchmark** (`load-tests/catalog-browse.js`) keeps 100 virtual users requesting store listings for 30 seconds, picking a random store (3) and category (6, or all) each time. The application is restarted for each setting, warmed up for 10 seconds, and measured three times with caching off (`CACHE_TYPE=none`) and on.

Table: Benchmark environment {#tbl:env widths=28,72}
| Item | Value |
|---|---|
| Machine | Apple MacBook with M3 (8 CPU cores), 16 GB memory |
| Application | DashCart on Java 21, run on the host JVM; default HikariCP pool of 10 connections |
| Dependencies | MySQL 8.4, Redis 7.4 and Kafka 4.3 in Docker containers on the same machine |
| Load generator | k6 v2.3.0, on the same machine |
| Rush scenario | 500 buyers, 100 units, one product, one store; 3 runs per strategy; median reported |
| Listing scenario | 100 virtual users for 30 s after a 10 s warm-up; 3 runs per setting; median reported |
| Raw data | `load-tests/results/` in the repository: one JSON file per run and a summary per experiment |

Running the load generator and the databases on the same laptop as the application means they compete for CPU, so absolute numbers are lower than on dedicated servers. All strategies and settings were measured under the same conditions, though, so the comparisons between them are fair.

## 5.9 Results: correctness, and the lock-late optimisation

**Correctness.** @fig:units-sold shows the units sold in the rush. NAIVE accepted all 500 orders for 100 units, overselling by 400, and it did so in every one of the six runs (three before and three after the lock-late change). Each of the four other strategies sold exactly 100 units in every run and rejected the other 400 buyers with 409, with no errors and with stock left at 0 available and 100 reserved. NAIVE didn't oversell occasionally or only under extreme timing: under a realistic rush it oversold every time, by the full number of extra buyers.

![Units sold in the flash-sale rush (500 buyers, 100 units)](build/charts/units-sold.png){#fig:units-sold}

**Before the optimisation.** @tbl:rush-baseline gives the first full results, with stock reserved at the start of the checkout transaction.

Table: Rush results before the optimisation, reserve first (median of 3 runs) {#tbl:rush-baseline widths=30,14,14,14,14,14}
| Strategy | Units oversold | Median ms | p95 ms | All served ms | Orders/s |
|---|---|---|---|---|---|
| NAIVE | 400 | 1,052 | 1,594 | 1,653 | 303 |
| OPTIMISTIC | 0 | 991 | 1,086 | 1,131 | 88 |
| PESSIMISTIC | 0 | 472 | 594 | 622 | 161 |
| CONDITIONAL_UPDATE | 0 | 406 | 524 | 568 | 176 |
| REDIS | 0 | 470 | 556 | 589 | 170 |

In every run NAIVE created 500 orders; each other strategy created exactly 100 and rejected 400 buyers with 409. "All served" is the slowest response, which is when the last of the 500 buyers got an answer; "Orders/s" divides the successful orders by that time.

**Finding the bottleneck.** The application's own metrics during these runs pointed at the cause. HikariCP reported requests waiting for a database connection: a mean wait of 49 ms and a maximum of 646 ms. The obvious reaction would be a bigger connection pool. But connections were scarce because each was held by a transaction queued on the same stock-row lock, and the lock was held for most of each transaction. A bigger pool would only let more transactions join the queue. This reasoning led to the lock-late change (Section 5.4).

To test the reasoning directly, I later ran the rush with the conditional update and the lock-late code, once with the default pool of 10 connections and once with 30. For each size the application was started fresh and given two unrecorded warm-up rushes, then measured three times (@tbl:pool). With three times as many connections, requests did wait less for a connection, but checkout became slower, not faster: the median rose from 412 to 479 ms and the last buyer was served at 664 ms instead of 564 ms. The runs varied (390–481 ms for the same pool of 10), so the safe conclusion is that a bigger pool didn't help. Extra connections only put more transactions in the queue for the same row lock, and on an 8-core laptop that also runs the database and the load generator, more concurrent transactions also compete for CPU. This matches the HikariCP project's guidance that a small pool usually outperforms a large one [32]. These runs were made in a separate session from @tbl:rush-locklate, on a database that had grown with earlier runs, so only pool 10 and pool 30 should be compared with each other.

Table: Connection pool size, conditional update with lock late (median of 3 runs after warm-up) {#tbl:pool widths=16,14,14,16,14,26 size=10}
| Pool size | Median ms | p95 ms | All served ms | Oversold | Mean connection wait |
|---|---|---|---|---|---|
| 10 (default) | 412 | 547 | 564 | 0 | 44.6 ms |
| 30 | 479 | 553 | 664 | 0 | 36.7 ms |

The connection wait is measured across the whole experiment, including each run's setup traffic (registrations, logins and carts).

**After the optimisation.** @tbl:rush-locklate and @fig:lock-late-chart compare the results with the lock taken last.

Table: Rush results after the lock-late change (median of 3 runs) {#tbl:rush-locklate widths=30,14,14,14,14,14}
| Strategy | Units oversold | Median ms | p95 ms | All served ms | Orders/s |
|---|---|---|---|---|---|
| NAIVE | 400 | 797 | 1,087 | 1,149 | 435 |
| OPTIMISTIC | 0 | 1,193 | 1,309 | 1,335 | 75 |
| PESSIMISTIC | 0 | 394 | 485 | 519 | 193 |
| CONDITIONAL_UPDATE | 0 | **340** | **462** | **494** | **202** |
| REDIS | 0 | 419 | 534 | 576 | 174 |

![Checkout time before and after the lock-late change](build/charts/lock-late.png){#fig:lock-late-chart}

Lock late made all three locking strategies faster. The median fell by 16% for the conditional update (406 to 340 ms), 17% for pessimistic locking (472 to 394 ms) and 11% for Redis (470 to 419 ms). The time for the last buyer to be served fell by 13%, 17% and 2% respectively. The conditional update was the fastest correct strategy before and after: it needs one statement per item where pessimistic locking needs two, and a lock held for less time means a shorter queue.

The change made **optimistic locking 20% slower** (991 to 1,193 ms), and the reason is instructive. Optimistic locking detects a conflict only at its conditional write. Before the change that write came first, so a losing attempt had done almost nothing when it found out it had lost. After the change the write comes last, so a losing attempt has already inserted the order and its items and deleted the cart lines, all of which must be rolled back and done again on the retry. Under a rush on a single row most attempts lose, so the wasted work multiplies. Optimistic locking suits workloads where conflicts are rare; a flash sale is the opposite. The other strategies make losers wait instead of redoing their work.

NAIVE also became faster (1,052 to 797 ms) but still oversold by 400 every time, which is a reminder that speed is meaningless without correctness.

## 5.10 Optimisation 2: caching product listings in Redis

The most frequent request in a quick-commerce app is not checkout but browsing: every customer opens a store's listing many times per order. A listing is a paged query that joins `store_inventory`, `products` and `categories` and also counts the matching rows for paging. That is cheap per request, but it adds up under load and competes with checkout for the same database.

**Design.** `CatalogService.storeProducts` is annotated with Spring's `@Cacheable` [12], with a key made of every parameter that changes the result: store, category, search text, page and page size. The cache is Redis-backed. The first request for a key runs the query and stores the serialised page in Redis for 30 seconds; later requests for the same key are answered from Redis without touching MySQL (@fig:cache-sequence). This is the *cache-aside* pattern.

![Cache-aside reads and after-commit invalidation](build/diagrams/cache-sequence.png){#fig:cache-sequence width=5.8}

**Staleness, and why it is safe.** A cached listing can show a product as in stock for up to 30 seconds after it sold out. This is acceptable because the cache is used only for display: the cart re-checks stock when an item is added, and checkout makes the real decision against the database with one of the strategies above. A stale listing can never cause an oversell. For changes made by store staff (price, product details, stock counts, a store going offline), the cache is cleared explicitly, so customers see them immediately rather than after up to 30 seconds.

**The invalidation bug the tests caught.** The first version cleared the cache with Spring's `@CacheEvict(allEntries = true)`. The browse test sets a product's stock to zero, then reads the listing and expects the product to be gone. It failed: the listing still came from the cache. Tracing showed the eviction being called and then, a moment later, a cache hit for the old entry. The cause is in Spring's `Cache` contract [13]. `clear()`, the method that `allEntries = true` calls, is allowed to take effect later, for example when a cache provider defers it, so a read made straight afterwards may still find the old entries. `invalidate()` is the method whose contract is that every entry is gone by the time the next read happens. There was a second, subtler problem: an eviction that runs before the database transaction commits leaves a window in which another request can re-cache the old rows. The fix is a small `CatalogCache` component that registers the invalidation as an after-commit transaction synchronisation and uses `invalidate()`. With that change the test passes.

**Results.** @tbl:cache-results and @fig:cache-chart compare the listing benchmark with the cache off and on.

Table: Listing benchmark, 100 virtual users for 30 s (median of 3 runs) {#tbl:cache-results widths=20,16,16,16,16,16}
| Setting | Requests/s | Median ms | p95 ms | p99 ms | Errors |
|---|---|---|---|---|---|
| No cache | 1,743 | 54.9 | 81.6 | 114.8 | 0% |
| Redis cache | 12,011 | 6.7 | 17.1 | 34.1 | 0% |
| Change | 6.9× | −88% | −79% | −70% | |

![Listing throughput and response time with the cache off and on](build/charts/cache.png){#fig:cache-chart}

The cache multiplied throughput by 6.9 and cut the median response time from 54.9 ms to 6.7 ms, with every percentile improving. Two caveats keep this honest. The benchmark uses 21 distinct listing keys (3 stores × 7 category choices, first page), so after the warm-up nearly every request is a cache hit. Real traffic, with search terms and deeper pages, would have a lower hit rate and a smaller gain. On the other hand, a cache hit costs one Redis round trip regardless of how complex the query is, so the benefit grows with catalogue size and query cost. The design also scales with traffic: every application instance shares the same Redis cache.

## 5.11 Measuring in production: metrics

Load tests run on demand; metrics run all the time. `CheckoutService` records every checkout in a Micrometer [33] timer named `dashcart.checkout`, tagged with the strategy and the outcome (success, out of stock, or retries exhausted), and counts optimistic conflicts in `dashcart.checkout.conflicts`. Spring Boot Actuator exposes these, together with the HikariCP connection-pool metrics used in Section 5.9, at `/actuator/metrics` for administrators, and the health of MySQL and Redis at `/actuator/health` for load balancers. @lst:metrics shows the timer after one checkout.

Listing: Checkout timer from the metrics endpoint {#lst:metrics}
```json
GET /actuator/metrics/dashcart.checkout          (admin token)
{"name": "dashcart.checkout", "baseUnit": "seconds",
 "measurements": [{"statistic": "COUNT", "value": 1.0},
                  {"statistic": "TOTAL_TIME", "value": 0.028984208},
                  {"statistic": "MAX", "value": 0.028984208}],
 "availableTags": [{"tag": "strategy", "values": ["CONDITIONAL_UPDATE"]},
                   {"tag": "outcome", "values": ["success"]}]}
```

## 5.12 Choosing the default

Based on these results, CONDITIONAL_UPDATE is the default strategy (`CHECKOUT_STRATEGY` can override it). It never oversold, it was the fastest in both rounds of measurements, it is the simplest to reason about (one statement per item, no retries, no second data store to keep in step), and it relies only on the database the system already depends on. Pessimistic locking is a close second and is the better fit when the decision needs more than a comparison, for example checking several columns or calling application code while the row is locked. The Redis strategy shows its value differently: it turns away losing buyers without touching MySQL at all, which matters most when demand outnumbers stock by a wide margin and the database is the scarce resource. Its price is the extra machinery to keep two stores consistent. Optimistic locking is the wrong tool for flash sales but the right one for rarely contended updates, such as an admin editing a product. NAIVE stays in the code only as the baseline that proves the problem is real.
