# 7. Technologies Used

This chapter describes each key technology: what it is, how DashCart uses it, and where it is used in the real world. @tbl:stack summarises the stack.

Table: Technology stack {#tbl:stack widths=26,22,52}
| Area | Technology | Version used |
|---|---|---|
| Language | Java (OpenJDK) | 21 (LTS) |
| Framework | Spring Boot, Spring Framework | 4.1, 7 |
| Web, validation | Spring Web MVC, Jakarta Bean Validation | (Spring Boot managed) |
| Security | Spring Security, jjwt | 0.13 |
| Persistence | Spring Data JPA, Hibernate ORM, JdbcTemplate | (Spring Boot managed) |
| Database | MySQL (InnoDB), Flyway | 8.4 |
| Cache and counters | Redis, Spring Cache | 7.4 |
| Events | Apache Kafka (KRaft mode), Spring for Apache Kafka | 4.3 |
| Payments | Razorpay Orders API, Checkout, webhooks | Test mode |
| Observability | Spring Boot Actuator, Micrometer | (Spring Boot managed) |
| Testing | JUnit 5, MockMvc, Testcontainers | |
| Load testing | Grafana k6 | 2.3 |
| Packaging and cloud | Docker, Docker Compose, AWS (EC2, VPC, RDS, ElastiCache, Elastic Beanstalk) | |
| Tools | Maven, Git and GitHub, PlantUML, Claude Code | |

## 7.1 Java 21

Java 21 is a long-term-support release of the Java platform [40], which means it receives updates for years, the usual choice for production systems. DashCart uses several modern language features that make the code shorter and safer. **Records** are used for every request and response type and for composite keys, giving immutable data classes with `equals` and `hashCode` in one line. **Text blocks** hold SQL statements and the Redis Lua scripts readably in the source. **Switch expressions** express rules such as the next fulfilment step of an order (@lst:switch).

Listing: A switch expression in OrderStatus {#lst:switch}
```java
public OrderStatus nextFulfilmentStep() {
    return switch (this) {
        case PAID -> PACKED;
        case PACKED -> OUT_FOR_DELIVERY;
        case OUT_FOR_DELIVERY -> DELIVERED;
        default -> null;
    };
}
```

**Real-world use:** Java remains one of the most widely used languages for large backend systems, notably in banking, e-commerce and streaming; the Netflix example in the next section is one of them.

## 7.2 Spring Boot

Spring Boot [14] is a framework for building production-ready Spring applications with minimal configuration. It provides *starters* that bring in a coherent set of libraries (`spring-boot-starter-webmvc`, `-data-jpa`, `-security`, `-cache`, `-kafka`, `-actuator`), *auto-configuration* that sets up beans such as the data source, the Redis connection factory and the Kafka template from properties, and an embedded web server, so the application is a single runnable jar.

DashCart uses Spring Boot 4.1, built on Spring Framework 7. Beyond the basics, it relies on features that matter for correctness: programmatic transactions with `TransactionTemplate` [11] for the checkout retry loop, `TransactionSynchronization` callbacks to run work after a commit (Kafka events, cache invalidation, Redis compensation), the cache abstraction [12], Bean Validation on request records, `ProblemDetail` for errors, and `@ServiceConnection` for Testcontainers [16]. Configuration properties such as `dashcart.checkout.reservation-ttl` bind to typed fields, including durations like `PT10M`.

**Real-world use:** Netflix announced in 2018 that it was moving its Java services to Spring Boot as their core framework, replacing much of its own in-house platform [42]. More generally, Spring Boot is one of the most common foundations for Java backend services and microservices.

## 7.3 Spring Security and JSON Web Tokens

Spring Security [17] handles authentication and authorisation as a chain of servlet filters in front of the controllers. DashCart configures it to be **stateless**: no HTTP session, no cookies and therefore no CSRF token. Each request proves who it is with a JSON Web Token (JWT) [18]: a compact, URL-safe string with a header, a set of claims (user id, e-mail, role, token id, expiry) and a signature. Because the token is signed with HMAC-SHA256 using a secret only the server knows, the server can trust the claims without a database lookup on every request. The jjwt library [21] creates and verifies the tokens. Passwords are stored as BCrypt hashes, which are deliberately slow to compute so that leaked hashes are hard to crack.

The weakness of stateless tokens is that they can't be "un-issued": a stolen token stays valid until it expires. DashCart keeps the expiry short (one hour) and supports logout with a deny-list of token ids in Redis, each entry expiring when the token would have.

**Real-world use:** JWTs are the token format of OpenID Connect [46], the identity layer used by "Sign in with Google" and most enterprise single sign-on systems, and are widely used as API access tokens.

## 7.4 MySQL, Hibernate and Flyway

**MySQL** stores DashCart's tables and enforces their constraints. Its default storage engine, InnoDB, gives each transaction all-or-nothing behaviour (the ACID properties), locks individual rows rather than whole tables, and keeps older versions of rows so that plain reads never wait for writers. The reservation strategies depend directly on InnoDB's behaviour: non-locking consistent reads for the precheck, `SELECT ... FOR UPDATE` locking reads [6], the locks taken by `UPDATE` statements [7], and deadlock detection [8]. DashCart uses MySQL 8.4, a long-term-support release, and its `CHECK` constraints and `JSON` column type.

**Hibernate ORM** [41], through Spring Data JPA, maps the entities to tables and generates simple queries from repository method names (`findByUserId`). For statements where the exact SQL matters, such as conditional stock updates, DashCart uses `JdbcTemplate` directly. **Flyway** [29] applies the numbered SQL migrations at start-up and records them in its own history table, so every environment, from a test container to RDS, has exactly the same schema.

**Real-world use:** GitHub runs GitHub.com on a large fleet of MySQL servers and described its upgrade of that fleet to MySQL 8.0 in 2023 [43]. Relational databases with row-level locking remain the standard place to keep money and stock, precisely because of the guarantees this project relies on.

## 7.5 Redis

Redis keeps its whole dataset in RAM and processes commands one after another on a single thread, which is why a typical request is answered in well under a millisecond. Beyond strings it offers expiry on any key, atomic counters and server-side Lua scripts that run without interruption [9]. DashCart uses Redis in three ways, each relying on a different property:

1. **Cache** for product listings: values with a 30-second time-to-live (Section 5.10).
2. **Deny-list** for logged-out tokens: keys that expire on their own when the token would have.
3. **Stock counters** for the Redis reservation strategy: an atomic Lua script that checks and decrements several counters as one step (Section 5.3), with keys grouped by a hash tag so they stay together in a Redis Cluster [10].

**Real-world use:** caching database results in Redis is one of the most common performance techniques on the web; Stack Overflow, for example, has described using Redis as a shared cache layer in front of its SQL Server databases [44]. Atomic Redis counters are also widely used for rate limiting, inventory holds in ticketing and flash sales, and leaderboards.

## 7.6 Apache Kafka

Apache Kafka [26] is a distributed event-streaming platform. Producers append messages to named **topics**, which are split into **partitions**; each partition is an ordered, durable log. Consumers read the log at their own pace and remember their position, and consumers in the same **consumer group** share the partitions between them. Kafka was created at LinkedIn to move activity and log data between systems [27], and is now used by many large organisations [45]. Recent versions run without ZooKeeper, using Kafka's built-in Raft-based controller (KRaft); DashCart uses Kafka 4 in KRaft mode.

![Order events through Kafka](build/diagrams/kafka-flow.png){#fig:kafka width=3.6}

@fig:kafka shows how DashCart uses it. Every order status change publishes an event to the `order-events` topic. The message key is the order id, so all events of one order go to the same partition and are consumed in the order they happened. Events are sent only after the database transaction commits, with `acks=all` and idempotent producer settings. The `dashcart-notifications` consumer group turns events into customer notifications. Kafka delivers *at least once*, so the same event can arrive twice. The consumer is idempotent, because the notifications table has a unique key on (order, status). The benefit is decoupling: checkout doesn't wait for, or fail because of, notifications. More consumers, such as a rider-assignment service, an analytics pipeline or a stock-replenishment alert, could subscribe to the same events without any change to checkout.

**Real-world use:** besides LinkedIn, Kafka is the backbone for event streaming at companies across finance, retail and transport [45]: activity tracking, log aggregation, change-data capture between databases, and real-time order and payment pipelines like the one sketched here.

## 7.7 Razorpay

Razorpay is an Indian payment gateway that lets a business accept UPI, cards, net banking and wallets through one integration. Its flow has three parts. The server creates a Razorpay **order** for the amount [22]. The customer pays in Razorpay **Checkout**, which returns a payment id and a signature that the server verifies [23]. Razorpay sends **webhooks**, signed server-to-server notifications of events such as `payment.captured`, which the server verifies with a separate secret [25]. DashCart implements all three, in test mode, and handles the realistic complications: repeated and out-of-order notifications, multiple payment attempts per order, and payments that arrive after the reservation expired (Section 5.6). Without API keys, the application uses a built-in fake gateway so that the whole flow can be tested offline.

**Real-world use:** payment gateways such as Razorpay sit behind most Indian online checkouts. The signature-verification and idempotent-webhook patterns used here apply to any payment provider.

## 7.8 Docker and Docker Compose

Docker packages an application with everything it needs to run into an **image**, and runs images as isolated **containers**. DashCart's multi-stage Dockerfile [34] produces a small runtime image (Section 6.1), and Docker Compose describes the whole local system (MySQL, Redis, Kafka and the API) in one file, started with one command. The same container images run on a laptop and on AWS, which removes the classic "works on my machine" problem.

**Real-world use:** containers are the standard unit of deployment for cloud services, whether on Kubernetes, Amazon ECS or Elastic Beanstalk.

## 7.9 Testcontainers and JUnit

Testcontainers [30] is a library that starts real services in Docker containers from test code and removes them afterwards. DashCart's 39 integration tests use it to run against MySQL 8.4, Redis 7.4 and Kafka 4.3, the same versions as production, so that locking behaviour, SQL dialect, Lua scripts and Kafka delivery are all tested for real. Spring Boot's `@ServiceConnection` [16] points the application at the containers automatically.

Another 37 unit tests use JUnit and Mockito without any containers, and JaCoCo reports the coverage of the whole suite (Section 5.7).

**Real-world use:** Testcontainers is used by teams that need integration tests to reflect production, especially where an in-memory substitute such as H2 would behave differently from the real database.

## 7.10 Grafana k6

k6 [31] is an open-source load-testing tool. Test scenarios are written in JavaScript and executed by a high-performance engine that simulates many virtual users. DashCart's two scenarios (Section 5.8) use k6's `setup` and `teardown` phases to prepare data and read back the final stock, custom metrics for created, rejected and failed checkouts, and a summary hook that writes each run's results as JSON for the repository.

**Real-world use:** load tests like these are run before sales events, product launches and capacity changes, and in CI pipelines to catch performance regressions.

## 7.11 Amazon Web Services

AWS provides the infrastructure described in Chapter 6: EC2 virtual machines, a VPC network with security groups, RDS for managed MySQL, ElastiCache for managed Redis, and Elastic Beanstalk to deploy and operate the application. Managed services shift backups, patching and failover to the provider, which matters for a small team.

**Real-world use:** cloud platforms like AWS let a small team run the same kind of infrastructure as a large company, paying only for what it uses. AWS has regions in Mumbai and Hyderabad, which keep data and latency close to Indian users.

## 7.12 Development tools

**Maven** builds the project and manages dependencies through the Maven Wrapper, so no local installation is needed. **Git and GitHub** hold the code, with one commit per development iteration. **PlantUML** produced the diagrams in this report from text, so they are versioned next to the code. **Claude Code** [1], an AI coding assistant, was used throughout development as described in Section 1.6.
