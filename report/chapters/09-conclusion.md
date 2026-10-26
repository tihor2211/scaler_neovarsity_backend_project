# 8. Conclusion

DashCart set out to build a quick-commerce backend that stays correct when many customers compete for the same stock, and to show it with measurements rather than assertions. The objectives of Section 1.3 were met as follows:

- A complete backend covers accounts, stores and pincodes, a per-store catalogue and inventory, carts, checkout, Razorpay payments, the order lifecycle and notifications: 33 endpoints, 13 tables and 39 integration tests against real MySQL, Redis and Kafka.
- Checkout never oversells with any of the four safe strategies. In every run of a 500-buyer rush on 100 units they sold exactly 100, while the naive baseline sold 500.
- Five concurrency-control techniques were compared under identical load. The conditional `UPDATE` was the fastest correct strategy and became the default.
- Two optimisations were measured before and after. Lock late cut the median checkout time of the locking strategies by 11–17% and, as a measured side effect, made optimistic locking 20% slower. The Redis listing cache raised listing throughput 6.9 times.
- Payments are verified by signature, confirmed exactly once whether the browser or the webhook arrives first, and flagged for refund when they arrive after the reservation expired.
- Order events flow through Kafka to an idempotent consumer, decoupled from checkout.
- The application is packaged as a layered, non-root Docker image for deployment on AWS.

## 8.1 Key takeaways

> <<Rohit: write this section in your own words, 4–6 short paragraphs or bullets on what you learned. Ideas you could reflect on: what surprised you about the naive strategy overselling every time; why a database row lock alone didn't prevent the lost update; why the "obvious" fix for the bottleneck (a bigger connection pool) was the wrong one; why the same change helped three strategies and hurt optimistic locking; what idempotency means in practice for payments and Kafka; what you learned about directing an AI coding assistant and reviewing its work.>>

## 8.2 Practical applications

The techniques in this project carry over directly to other domains:

- **Anything sold in limited quantity**, such as event tickets, flash-sale products, limited coupons and vaccination or appointment slots, needs the same all-or-nothing reservation with a conditional update or lock, plus a reservation deadline.
- **Payment integrations** with any provider need the same three safeguards: verify every notification's signature over the exact bytes received, deduplicate by event id, and treat "already done" as success.
- **Event-driven side effects**, such as notifications, analytics, search indexing and fulfilment, can be moved off the request path with an event stream, as long as events are published only after the change is committed and consumers are idempotent.
- **Caching hot reads** works best when staleness is harmless by design: the cache serves display, and the decision is always made against the source of truth.
- **Evidence over intuition.** Measuring before optimising revealed both an improvement (lock late) and a regression that intuition would have missed (optimistic locking).

## 8.3 Limitations

**Technical limitations.**

- *Events can be lost in a crash.* Kafka events are sent after the commit, so a consumer never sees a change that was rolled back. If the application crashes between the commit and the send, however, or Kafka is unreachable, the event is lost and only logged. The standard fix is a *transactional outbox* [28]: write the event to a table in the same transaction and publish it from there.
- *The Redis strategy can drift.* If the application crashed after the Lua script decremented Redis but before the transaction finished, Redis would show less stock than MySQL. This can't cause an oversell, because MySQL's conditional update has the final say, but it could turn buyers away while units remain, until an inventory update or a restart reloads the counters. A periodic reconciliation job would close this gap.
- *Single instance and single broker.* The deployment runs one application instance and one Kafka broker on the same machine, so there is no redundancy. The application itself is stateless, and the expiry job is safe to run on several instances, so scaling out is mainly an infrastructure change.
- *Benchmarks on one laptop.* The application, databases and load generator shared one machine, so absolute numbers are lower than dedicated servers would give. The comparisons are fair, but they were measured on one product with a single store; a sale across many products would contend less on each row.
- *Search and refunds.* Product search uses `LIKE '%term%'`, which can't use an index and won't scale to a large catalogue. Refunds are flagged, not issued.
- *Fairness under contention.* None of the strategies guarantees strict first-come, first-served. Under optimistic locking a buyer can lose five times in a row and be told to try again while stock remains.

**Cost implications.** The deployment fits the AWS free tier or a few dollars of credit only because it avoids components with a fixed monthly cost: a load balancer, a NAT gateway, Multi-AZ databases and managed Kafka (MSK). A production deployment would need most of these, and managed Kafka would likely cost more than the rest of the infrastructure combined at this scale. A hosted Kafka-compatible service, or a simpler queue such as Amazon SQS for the notification use case, would be cheaper alternatives.

## 8.4 Future work

1. **Transactional outbox** for order events, so no event is lost.
2. **A waiting room for flash sales.** Admit buyers to checkout at a controlled rate using a Redis queue, so the database sees steady load however large the crowd.
3. **Rider assignment and live tracking** as a separate service consuming the existing order events.
4. **Full-text search** with MySQL full-text indexes or a search engine such as OpenSearch.
5. **Load-balanced, auto-scaling deployment** with HTTPS, Multi-AZ RDS and a replicated cache, plus a CI/CD pipeline (for example GitHub Actions) that runs the tests and deploys on every merge.
6. **Virtual threads** (Java 21) for request handling, a one-line Spring Boot setting, measured with the same benchmarks.
7. **Dashboards and alerts** with Prometheus and Grafana on top of the existing Micrometer metrics.
