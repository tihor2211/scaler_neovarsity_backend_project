# Appendix A: API Reference

All endpoints accept and return JSON. "Customer" endpoints need a valid access token (`Authorization: Bearer <token>`); "Admin" endpoints need a token with the ADMIN role. Errors use the problem-details format of Section 3.8.

Table: Accounts, stores and catalogue endpoints {#tbl:api-1 widths=10,35,12,43 size=10}
| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | /auth/register | Public | Create a customer account; 201 with the profile |
| POST | /auth/login | Public | Exchange e-mail and password for an access token |
| POST | /auth/logout | Customer | Deny-list the current token until it expires; 204 |
| GET | /users/me | Customer | The logged-in user's profile |
| GET | /stores/serviceable?pincode= | Public | The store serving a pincode, or 404 |
| GET | /categories | Public | All categories (cached) |
| GET | /stores/{id}/products | Public | Paged listing of a store's in-stock products; `categoryId`, `search`, `page`, `size` (cached) |
| GET | /stores/{id}/products/{productId} | Public | One product with its stock in that store |
| POST | /admin/stores | Admin | Create a dark store |
| GET | /admin/stores | Admin | All stores with their pincodes |
| PUT | /admin/stores/{id}/pincodes | Admin | Assign pincodes (moves them from another store if needed) |
| PUT | /admin/stores/{id}/active | Admin | Switch a store on or off |
| POST | /admin/categories | Admin | Create a category |
| POST | /admin/products | Admin | Create a product (price ≤ MRP) |
| PUT | /admin/products/{id} | Admin | Update a product, including deactivating it |
| GET | /admin/stores/{id}/inventory | Admin | Stock of every product in a store: available and reserved |
| PUT | /admin/stores/{id}/inventory | Admin | Set available stock for one or more products |

Table: Cart, orders, payments and operations endpoints {#tbl:api-2 widths=10,35,12,43 size=10}
| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | /cart | Customer | The cart with totals, savings and per-line availability |
| PUT | /cart/items/{productId} | Customer | Set a product's quantity (`storeId`, `quantity`; 0 removes) |
| DELETE | /cart | Customer | Empty the cart; 204 |
| POST | /orders/checkout | Customer | Create an order and reserve its stock; 201, or 409 if stock is short |
| GET | /orders | Customer | The customer's orders, newest first, paged |
| GET | /orders/{id} | Customer | One order with its items |
| POST | /orders/{id}/cancel | Customer | Cancel an unpaid order and release its stock |
| POST | /orders/{id}/payment | Customer | Start a payment attempt: creates a Razorpay order; 201 |
| GET | /orders/{id}/payments | Customer | Payment attempts of an order |
| POST | /payments/razorpay/verify | Customer | Confirm a payment with Razorpay Checkout's ids and signature |
| POST | /payments/razorpay/webhook | Razorpay (signed) | Payment events; authenticated by HMAC signature, not a token |
| GET | /notifications | Customer | The customer's order notifications, newest first |
| POST | /admin/orders/{id}/advance | Admin | Move a paid order to its next fulfilment step |
| POST | /admin/orders/expire-due | Admin | Run the expiry job now (demos and tests) |
| GET | /admin/checkout/strategy | Admin | Active and available reservation strategies |
| PUT | /admin/checkout/strategy | Admin | Switch the reservation strategy at runtime |
| GET | /actuator/health | Public | Health of the application, MySQL and Redis |
| GET | /actuator/metrics/{name} | Admin | Metrics such as `dashcart.checkout` and `hikaricp.connections.acquire` |

# Appendix B: Running the Project

The source code is at https://github.com/tihor2211/scaler_neovarsity_backend_project. It needs Docker, plus Java 21 to run the tests and k6 to run the load tests.

```bash
# The whole system: MySQL, Redis, Kafka and the API on http://localhost:8080
docker compose --profile app up -d --build
curl http://localhost:8080/actuator/health

# Tests: start their own containers, 39 tests
./mvnw verify

# Flash-sale rush against every strategy (app running, admin credentials set)
./load-tests/run-rush-benchmark.sh

# Listing benchmark with the cache off and on (starts the app itself)
./mvnw -DskipTests package && ./load-tests/run-cache-benchmark.sh
```

Copy `.env.example` to `.env` to set the first administrator's e-mail and password, a JWT secret and Razorpay test keys. Without Razorpay keys a built-in fake gateway is used, so the whole flow, including payments, works offline.
