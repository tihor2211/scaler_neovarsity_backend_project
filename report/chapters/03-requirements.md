# 2. Requirement Gathering

The requirements below come from how quick-commerce apps behave from a customer's point of view, from the operational needs of a store team, and from the failure cases in Section 1.2. They are written so that each one can be checked: most map directly to an endpoint and to one or more integration tests.

## 2.1 Users and actors

Table: Actors of the system {#tbl:actors widths=20,80}
| Actor | Description |
|---|---|
| Customer | Registers, finds the store for their pincode, browses, manages a cart, checks out, pays, cancels unpaid orders and reads order notifications. Browsing works without logging in. |
| Store admin | Operations staff. Creates stores and assigns pincodes, manages the catalogue, sets stock levels, moves paid orders through packing and delivery, and can switch the checkout strategy and read metrics. Admin accounts can't self-register; the first admin is created from configuration at start-up. |
| Razorpay | External payment gateway. Creates payment orders on request and notifies DashCart of payment outcomes through signed webhooks. |
| Scheduler | A timer inside the application that runs the reservation-expiry job every 30 seconds. |

## 2.2 Use cases

@fig:usecases shows the customer's use cases and @fig:usecases-ops those of the store admin, Razorpay and the scheduler. Two use cases are included in others. Checking out always includes reserving stock (all or nothing). Paying, through the browser or the webhook, always includes verifying Razorpay's signature.

![Use cases: shopping](build/diagrams/use-cases.png){#fig:usecases width=5.2}

![Use cases: operations](build/diagrams/use-cases-operations.png){#fig:usecases-ops width=5.6}

The two use cases where most of the project's complexity lies are described in detail in @tbl:uc-checkout and @tbl:uc-pay.

Table: Use case specification: Check out {#tbl:uc-checkout widths=22,78}
| Field | Description |
|---|---|
| Actor | Customer (logged in) |
| Goal | Turn the cart into an order whose stock is held for the customer while they pay. |
| Preconditions | The cart has at least one item; the store is active; the delivery pincode is served by the cart's store. |
| Main flow | 1. Customer submits the delivery address and pincode. 2. System validates the cart, the pincode and that every product is still sold. 3. System creates the order with status CREATED and a payment deadline ten minutes ahead, copies the items with their current prices and empties the cart. 4. System reserves the stock of every item: available quantity goes down and reserved quantity goes up. 5. System returns the order (201 Created) and publishes an order-created event. |
| Alternate flows | A. An item doesn't have enough stock: nothing is saved, the cart is kept and the customer gets 409 Conflict naming the item. B. Pincode not served by the cart's store: 400 Bad Request. C. Under optimistic locking, a conflicting update causes an automatic retry (up to five attempts) before 409. |
| Postconditions | Exactly one of: (a) order CREATED with all its stock reserved and the cart empty, or (b) no change at all. |
| Business rule | Across all concurrent checkouts, the total quantity reserved and sold for a product in a store never exceeds the stock that was available. |

Table: Use case specification: Pay for order {#tbl:uc-pay widths=22,78}
| Field | Description |
|---|---|
| Actors | Customer, Razorpay |
| Goal | Collect payment for an order and confirm the order exactly once. |
| Preconditions | Order is CREATED or PAYMENT_PENDING and its payment deadline hasn't passed. |
| Main flow | 1. Customer starts payment. 2. System asks Razorpay to create a payment order for the order total (in paise) and records a payment attempt; the order becomes PAYMENT_PENDING. 3. Customer pays in Razorpay Checkout. 4. The app forwards Razorpay's payment id and signature; the system verifies the HMAC-SHA256 signature and marks the order PAID, turning reserved stock into sold stock. 5. Razorpay also sends a signed `payment.captured` webhook; the system verifies it, records the event id and finds the payment already captured, so nothing changes. |
| Alternate flows | A. Webhook arrives before the browser confirmation: the webhook captures and the browser call finds the work done. B. Invalid signature: 400, nothing changes. C. Payment arrives after the order expired or was cancelled: the payment is recorded as REFUND_REQUIRED and the order stays as it was. D. Payment fails: the attempt is marked FAILED and the customer can try again with a new attempt. |
| Postconditions | The order is PAID at most once, by exactly one captured payment; every webhook event id is processed at most once. |

## 2.3 Functional requirements

Table: Functional requirements {#tbl:fr widths=9,59,32}
| ID | Requirement | Endpoint(s) |
|---|---|---|
| FR-01 | A visitor can register with name, e-mail, optional Indian mobile number and a password of 8–72 characters; e-mails are unique, case-insensitive. | POST /auth/register |
| FR-02 | A user can log in and receive a signed access token valid for one hour; wrong e-mail and wrong password give the same error. | POST /auth/login |
| FR-03 | A user can log out; the token stops working immediately, before its expiry. | POST /auth/logout |
| FR-04 | Anyone can check whether a 6-digit pincode is served and by which store. | GET /stores/serviceable |
| FR-05 | Anyone can list categories and browse a store's in-stock products by category and name search, with paging. | GET /categories, GET /stores/{id}/products |
| FR-06 | A customer has one cart, tied to one store; adding from another store requires clearing the cart. | PUT /cart/items/{id}, DELETE /cart |
| FR-07 | The cart refuses quantities above the stock available or above 10 of one item, and shows totals and savings against MRP. | GET /cart |
| FR-08 | Checkout creates an order and reserves stock for all items or for none (see @tbl:uc-checkout). | POST /orders/checkout |
| FR-09 | Unpaid orders expire ten minutes after checkout and their stock is released automatically. | scheduled job; POST /admin/orders/expire-due |
| FR-10 | A customer can cancel an order until it is paid; its stock is released. | POST /orders/{id}/cancel |
| FR-11 | A customer can start a Razorpay payment; each attempt gets its own Razorpay order. | POST /orders/{id}/payment |
| FR-12 | Payments are confirmed by a signature-checked browser callback or webhook, whichever comes first, exactly once (see @tbl:uc-pay). | POST /payments/razorpay/verify, /webhook |
| FR-13 | Customers can list their orders (newest first) and see one order with its items; other customers' orders are invisible. | GET /orders, GET /orders/{id} |
| FR-14 | Every order status change produces a customer notification. | GET /notifications |
| FR-15 | Admins can create stores, assign pincodes and switch a store on or off. | /admin/stores/** |
| FR-16 | Admins can create categories and products and edit products (price can't exceed MRP). | /admin/categories, /admin/products |
| FR-17 | Admins can set the stock of products in a store. | PUT /admin/stores/{id}/inventory |
| FR-18 | Admins can move a paid order through PACKED, OUT_FOR_DELIVERY and DELIVERED, in that order only. | POST /admin/orders/{id}/advance |
| FR-19 | Admins can see and switch the active stock-reservation strategy without a restart. | GET/PUT /admin/checkout/strategy |
| FR-20 | Admins can read application metrics; anyone can read the health status. | /actuator/metrics, /actuator/health |

## 2.4 Non-functional requirements

Table: Non-functional requirements {#tbl:nfr widths=18,52,30}
| Quality | Requirement | How it is met / verified |
|---|---|---|
| Correctness under concurrency | No product is ever oversold, whatever the number of simultaneous checkouts. | Four safe strategies; concurrency test (40 buyers, 15 units) and k6 rush (500 buyers, 100 units). |
| Performance | During a 500-buyer rush on one product, every buyer gets an answer within about 0.5 s with the default strategy on a laptop; cached listings answer in single-digit milliseconds. | k6 benchmarks in Chapter 5. |
| Consistency | Stock, orders and payments change together or not at all; events and cache invalidations happen only after a commit. | Single database transactions; after-commit hooks. |
| Idempotency | Repeated webhooks, repeated browser callbacks and redelivered Kafka events have no extra effect. | Unique keys on webhook event id and on (order, status) notifications; status checks before capture. |
| Security | Passwords hashed (BCrypt); stateless signed tokens; role-based access to admin endpoints; payment notifications trusted only with a valid HMAC signature; no secrets in the repository. | Spring Security configuration; auth and payment tests. |
| Availability | A slow payment gateway never holds database locks; Kafka being down doesn't block checkout. | Gateway call made outside transactions; asynchronous event sending. |
| Observability | Health, checkout latency per strategy and outcome, and conflict counts are exposed. | Spring Boot Actuator and Micrometer. |
| Maintainability | Schema changes are versioned; the strategies share one interface; errors follow one format (RFC 9457). | Flyway; Strategy pattern; global exception handler. |
| Portability | Runs anywhere Docker runs, with configuration from environment variables. | Dockerfile, Docker Compose, `.env.example`. |

## 2.5 Feature set

@tbl:features groups the features by module. The full list of the 33 endpoints is in Appendix A.

Table: Feature set by module {#tbl:features widths=17,58,25}
| Module | Features | Access |
|---|---|---|
| Accounts | Registration with validation, login with JWT, logout with a Redis deny-list, current-user profile, admin seeding | Public / customer |
| Stores | Pincode serviceability, store creation, pincode assignment, store on/off | Public / admin |
| Catalogue | Categories, products with MRP and selling price, store listing with category filter, name search and paging, Redis cache | Public / admin |
| Inventory | Per-store stock with available and reserved quantities, bulk stock update | Admin |
| Cart | One cart per user and store, quantity limits, live stock check, totals and savings | Customer |
| Checkout | All-or-nothing stock reservation with five strategies, retries for optimistic locking, metrics per strategy | Customer / admin |
| Orders | Order history, order detail, cancel, ten-minute expiry, fulfilment steps | Customer / admin |
| Payments | Razorpay order per attempt, browser verification, webhook with deduplication, late-payment refund flag, demo payment page | Customer / Razorpay |
| Notifications | Kafka order events, idempotent consumer, notification inbox | Customer |

## 2.6 Assumptions and constraints

- Each pincode is served by exactly one dark store, and a cart holds items from one store only, as in the apps that inspired the project.
- Prices are stored in rupees with two decimals; Razorpay amounts are sent in paise, as its API requires [22].
- Razorpay is used in test mode. Refunds are flagged for manual handling rather than issued automatically.
- Timestamps are stored in UTC.
- The AWS deployment has to cost next to nothing, so it uses the smallest instance sizes, runs Kafka in a container instead of a managed service, and exists only while it is being verified (Chapter 6).
