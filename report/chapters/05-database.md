# 4. Database Schema Design

## 4.1 Design principles

MySQL 8.4 with the InnoDB storage engine is the system of record. The schema follows a few rules throughout:

- **The schema is code.** It is created and changed only by numbered Flyway migrations [29] in `src/main/resources/db/migration`. Hibernate is set to `ddl-auto=validate`: at start-up it checks that the entities match the tables and refuses to start if they don't, but it never changes the schema itself.
- **The database enforces what must never be false.** Uniqueness, foreign keys and `CHECK` constraints (stock never negative, price never above MRP, quantities positive) are declared in the schema, so even a bug in the application can't store such data.
- **Money is exact.** Rupee amounts are `DECIMAL(10,2)`, never floating point. Razorpay amounts are whole paise in a `BIGINT`.
- **Statuses are readable strings.** Enumerations such as order status are stored as `VARCHAR`, not MySQL `ENUM` and not ordinal numbers, so adding a status needs no table rebuild and the data reads clearly in any SQL client.
- **Time is UTC** and stored as `DATETIME(6)` (microseconds). Every table except the pure link tables has `created_at`, and mutable tables have `updated_at` maintained by MySQL (`ON UPDATE CURRENT_TIMESTAMP(6)`). These two columns are left out of the tables below.

## 4.2 Tables

The schema has thirteen tables. They are described below in the textual form the template asks for, then shown together in the ER diagram in Section 4.5.

### Accounts, stores and catalogue

Table: users {#tbl:t-users widths=24,24,52}
| Column | Type | Constraints and notes |
|---|---|---|
| id | BIGINT | Primary key, auto-increment |
| name | VARCHAR(100) | Not null |
| email | VARCHAR(255) | Not null, unique (`uk_users_email`); stored lower-case |
| phone | VARCHAR(15) | Optional; 10-digit Indian mobile number |
| password_hash | VARCHAR(100) | Not null; BCrypt hash, never the password |
| role | VARCHAR(20) | Not null; CUSTOMER or ADMIN |

Table: stores {#tbl:t-stores widths=24,24,52}
| Column | Type | Constraints and notes |
|---|---|---|
| id | BIGINT | Primary key |
| name | VARCHAR(100) | Not null |
| address | VARCHAR(255) | Not null |
| latitude, longitude | DECIMAL(9,6) | Not null; location of the dark store |
| active | BOOLEAN | Not null, default true; an inactive store takes no orders |

Table: serviceable_pincodes {#tbl:t-pincodes widths=24,24,52}
| Column | Type | Constraints and notes |
|---|---|---|
| pincode | CHAR(6) | Primary key: each pincode is served by exactly one store |
| store_id | BIGINT | Not null, foreign key to stores, indexed |

Table: categories and products {#tbl:t-catalogue widths=24,24,52}
| Column | Type | Constraints and notes |
|---|---|---|
| **categories**.id | BIGINT | Primary key |
| **categories**.name | VARCHAR(100) | Not null, unique |
| **products**.id | BIGINT | Primary key |
| category_id | BIGINT | Not null, foreign key to categories, indexed |
| name | VARCHAR(200) | Not null |
| description | TEXT | Optional |
| unit | VARCHAR(30) | Not null; pack size such as "500 g" or "6 pcs" |
| mrp, price | DECIMAL(10,2) | Not null; `CHECK (price > 0 AND price <= mrp)` |
| image_url | VARCHAR(500) | Optional |
| active | BOOLEAN | Not null; inactive products disappear from listings and can't be checked out |

### Stock and carts

`store_inventory` is the table the whole key feature revolves around. Each row is one product in one store and holds two numbers: `available_qty`, which can be sold now, and `reserved_qty`, which is held by orders waiting for payment. Checkout moves units from available to reserved, payment removes them from reserved (sold), and expiry or cancellation moves them back to available. The `version` column is used only by the optimistic-locking strategy.

Table: store_inventory {#tbl:t-inventory widths=24,24,52}
| Column | Type | Constraints and notes |
|---|---|---|
| store_id | BIGINT | Primary key (part 1), foreign key to stores |
| product_id | BIGINT | Primary key (part 2), foreign key to products, indexed |
| available_qty | INT | Not null; `CHECK (available_qty >= 0)` |
| reserved_qty | INT | Not null, default 0; `CHECK (reserved_qty >= 0)` |
| version | BIGINT | Not null, default 0; incremented on every stock change |

Table: carts and cart_items {#tbl:t-carts widths=24,24,52}
| Column | Type | Constraints and notes |
|---|---|---|
| **carts**.id | BIGINT | Primary key |
| user_id | BIGINT | Not null, unique (one cart per user), foreign key to users |
| store_id | BIGINT | Not null, foreign key to stores: the store the cart belongs to |
| **cart_items**.cart_id | BIGINT | Primary key (part 1), foreign key to carts, `ON DELETE CASCADE` |
| product_id | BIGINT | Primary key (part 2), foreign key to products |
| quantity | INT | Not null; `CHECK (quantity > 0)` |
| added_at | DATETIME(6) | When the item was added |

### Orders, payments and notifications

Table: orders {#tbl:t-orders widths=24,24,52}
| Column | Type | Constraints and notes |
|---|---|---|
| id | BIGINT | Primary key |
| user_id | BIGINT | Not null, foreign key to users |
| store_id | BIGINT | Not null, foreign key to stores |
| status | VARCHAR(20) | Not null; one of the eight statuses in @fig:order-states |
| total_amount | DECIMAL(10,2) | Not null; sum of the items at checkout prices |
| delivery_address | VARCHAR(500) | Not null |
| delivery_pincode | CHAR(6) | Not null |
| reservation_strategy | VARCHAR(20) | Not null; the strategy that reserved the stock, so it is released the same way |
| expires_at | DATETIME(6) | Not null; payment deadline (checkout time + 10 minutes) |

Table: order_items {#tbl:t-order-items widths=24,24,52}
| Column | Type | Constraints and notes |
|---|---|---|
| id | BIGINT | Primary key |
| order_id | BIGINT | Not null, foreign key to orders |
| product_id | BIGINT | Not null, foreign key to products; (order_id, product_id) unique |
| quantity | INT | Not null; `CHECK (quantity > 0)` |
| unit_price | DECIMAL(10,2) | Not null; price at checkout, kept even if the product price changes later |

Table: payments {#tbl:t-payments widths=24,24,52}
| Column | Type | Constraints and notes |
|---|---|---|
| id | BIGINT | Primary key |
| order_id | BIGINT | Not null, foreign key to orders, indexed; an order can have several attempts |
| razorpay_order_id | VARCHAR(64) | Not null, unique; one Razorpay order per attempt |
| razorpay_payment_id | VARCHAR(64) | Set when Razorpay reports a payment |
| amount_paise | BIGINT | Not null |
| currency | CHAR(3) | Not null, default INR |
| status | VARCHAR(20) | Not null; CREATED, CAPTURED, FAILED or REFUND_REQUIRED |

Table: webhook_events and notifications {#tbl:t-events widths=24,24,52}
| Column | Type | Constraints and notes |
|---|---|---|
| **webhook_events**.id | BIGINT | Primary key |
| provider | VARCHAR(20) | Not null; "razorpay" |
| event_id | VARCHAR(100) | Not null; Razorpay's `x-razorpay-event-id`; (provider, event_id) unique |
| event_type | VARCHAR(50) | Not null; for example `payment.captured` |
| payload | JSON | Not null; the raw event, kept for audit |
| received_at, processed_at | DATETIME(6) | When it arrived; when it was applied |
| **notifications**.id | BIGINT | Primary key |
| user_id, order_id | BIGINT | Not null, foreign keys to users and orders |
| status | VARCHAR(20) | Not null; the order status the message is about; (order_id, status) unique |
| message | VARCHAR(255) | Not null; text shown to the customer |

## 4.3 Foreign keys

- serviceable_pincodes(store_id) refers stores(id)
- products(category_id) refers categories(id)
- store_inventory(store_id) refers stores(id)
- store_inventory(product_id) refers products(id)
- carts(user_id) refers users(id)
- carts(store_id) refers stores(id)
- cart_items(cart_id) refers carts(id), deleting a cart deletes its items
- cart_items(product_id) refers products(id)
- orders(user_id) refers users(id)
- orders(store_id) refers stores(id)
- order_items(order_id) refers orders(id)
- order_items(product_id) refers products(id)
- payments(order_id) refers orders(id)
- notifications(user_id) refers users(id)
- notifications(order_id) refers orders(id)

`webhook_events` has no foreign keys on purpose: an event is recorded before it is applied, and it may refer to a Razorpay order DashCart doesn't know.

## 4.4 Cardinality of relations

- Between stores and serviceable_pincodes: 1:m (a store serves many pincodes, a pincode belongs to one store)
- Between categories and products: 1:m
- Between stores and products: m:m, through store_inventory (a store stocks many products, a product is stocked in many stores)
- Between users and carts: 1:0..1 (at most one cart per user)
- Between carts and products: m:m, through cart_items
- Between users and orders: 1:m; between stores and orders: 1:m
- Between orders and products: m:m, through order_items (an order has one or more items)
- Between orders and payments: 1:m (one row per payment attempt)
- Between orders and notifications: 1:m (at most one per status)

## 4.5 Entity-relationship diagram

@fig:erd shows all thirteen tables with their keys, in crow's-foot notation. The diagram is rotated to fit the page.

![Entity-relationship diagram of the DashCart schema (rotated)](build/diagrams/er-diagram-rotated.png){#fig:erd maxheight=8.2}

## 4.6 Indexes and the queries they serve

Every foreign key column has an index, which InnoDB requires. The additional indexes in @tbl:indexes each exist for a specific query.

Table: Indexes beyond primary keys {#tbl:indexes widths=30,26,44}
| Index | Columns | Serves |
|---|---|---|
| uk_users_email | users(email) | Login lookup and duplicate-registration check |
| uk_carts_user | carts(user_id) | "My cart" lookup; also enforces one cart per user |
| idx_orders_user_created | orders(user_id, created_at) | Order history, newest first, paged |
| idx_orders_status_expires | orders(status, expires_at) | The expiry job's "unpaid orders past their deadline" scan every 30 s |
| uk_payments_razorpay_order | payments(razorpay_order_id) | Finding the attempt for a verify call or a webhook |
| uk_webhook_events_provider_event | webhook_events(provider, event_id) | Deduplicating webhook deliveries |
| uk_notifications_order_status | notifications(order_id, status) | Deduplicating Kafka redeliveries |
| idx_notifications_user_created | notifications(user_id, created_at) | The customer's inbox, newest first |
| idx_inventory_product | store_inventory(product_id) | Stock of a product across stores |

The store listing reads `store_inventory` by its primary key prefix (`store_id`) and joins `products` and `categories` by primary key. The name search uses `LIKE '%term%'`, which can't use an index. With a few thousand products per store this costs little, and the Redis cache absorbs repeated searches, but a large catalogue would need a full-text index or a search engine (Section 8.3).

## 4.7 Constraints as the last line of defence, and their limits

The `CHECK (available_qty >= 0 AND reserved_qty >= 0)` constraint guarantees that stock never goes negative, whatever the application does. It is worth noting what it does **not** catch. In the lost-update scenario of Section 5.1, two checkouts both read one unit and both write `available_qty = 0`. Neither value is negative, so the constraint is satisfied while the store has promised the same unit twice. Preventing overselling therefore needs correct concurrency control in the write itself, which is what Chapter 5 is about. The constraint remains a valuable safety net for the strategies that compute `available_qty - ?` in the database.

## 4.8 Migrations

Table: Flyway migrations {#tbl:migrations widths=30,70}
| Migration | Change |
|---|---|
| V1__initial_schema | All core tables, keys, foreign keys and checks |
| V2__demo_data | Three Bengaluru dark stores with three pincodes each, six categories and thirty products, with stock |
| V3__payment_attempts | Allows several payment attempts per order: replaces the unique key on payments(order_id) with a plain index |
| V4__notifications | Notifications table written by the Kafka consumer |

V3 is an example of the schema evolving with the design. The first payment design allowed one payment row per order. Razorpay, however, models each payment attempt as its own Razorpay order, and a customer whose card fails may retry with UPI. The migration relaxed the constraint without touching existing data.

## 4.9 Data outside MySQL

Redis and Kafka hold data too, but none of it is the system of record: all of it can be rebuilt from MySQL or safely lost.

Table: Keys in Redis {#tbl:redis-keys widths=34,22,44}
| Key pattern | Lifetime | Purpose |
|---|---|---|
| `cache:storeProducts::{store}:{category}:{search}:{page}:{size}` | 30 s | Cached page of a store listing |
| `cache:categories::all` | 30 s | Cached category list |
| `auth:denylist:{tokenId}` | until the token would expire | Logged-out tokens |
| `stock:{storeId}:{productId}` | until changed | Available stock, used only by the REDIS strategy |

The braces in `stock:{storeId}:...` are a Redis Cluster *hash tag* [10]: only the part inside the braces decides which cluster node holds the key, so all keys of one store live together. This matters because the reservation script touches several products of one store in a single atomic call, and Redis Cluster only allows that when the keys are on the same node.

Kafka holds one topic, `order-events`, with three partitions. Each message is a small JSON document (order id, user id, store id, new status, total amount, time) keyed by order id (Section 7.6).
