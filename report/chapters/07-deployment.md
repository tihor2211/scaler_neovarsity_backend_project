# 6. Deployment Flow

DashCart was deployed on AWS in the Mumbai region (ap-south-1), in a VPC of its own:

- an EC2 instance running the API and a Kafka broker as Docker containers
- MySQL on Amazon RDS
- Redis-compatible caching on Amazon ElastiCache (Valkey)

The whole environment is created, deployed and deleted by three scripts in `deploy/aws`. On 30 October 2026 it went from nothing to a healthy API in seven minutes. It was verified end to end with the screenshots, tests and measurements in this chapter, and deleted after about thirty minutes, because the AWS account used had no free-tier allowance left (Section 6.6). This chapter explains the container image, each AWS component and why it was chosen, how a deployment runs, and what it costs.

## 6.1 Packaging the application as a container

The same image runs on a laptop and in the cloud. @lst:dockerfile shows the Dockerfile. It uses a multi-stage build [34]: the first stage has the full JDK and Maven and compiles the application; the second stage starts from a much smaller Java runtime image (JRE only) and copies in only what is needed to run.

Listing: Dockerfile (comments shortened) {#lst:dockerfile}
```dockerfile
# Build: compile and split the jar into layers
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B package -DskipTests \
    && cp target/dashcart-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Run: JRE only, non-root
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 --no-create-home dashcart
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER dashcart
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Four details make the image fast to rebuild and safe to run:

- **Layering.** Spring Boot can split its executable jar into layers ordered by how often they change: third-party dependencies, the Spring Boot loader, snapshot dependencies, and the application's own classes [15]. Each becomes a separate Docker layer, so a code change rebuilds and uploads only the last, small layer. The Maven dependencies are also resolved in their own step before the source code is copied, so they are downloaded again only when `pom.xml` changes.
- **Non-root user.** The process runs as an unprivileged `dashcart` user, which limits the damage if the application were ever compromised.
- **Memory sizing from the container.** `MaxRAMPercentage` sizes the Java heap from the container's memory limit rather than the host's, and `ExitOnOutOfMemoryError` makes the JVM exit, so Docker restarts it, instead of limping on after running out of memory.
- **Tests run before, not inside, the build.** The integration tests start their own containers through Testcontainers, so they run on the developer machine (`./mvnw verify`) before the image is built.

Locally, `docker compose --profile app up -d --build` starts MySQL, Redis, Kafka and the API together. Configuration comes only from environment variables, with secrets in a `.env` file that is excluded from version control.

## 6.2 Architecture on AWS

@fig:aws shows what was deployed. Every resource is named `dashcart-*` and tagged `Project=dashcart`, so it can be found, and deleted, as a group.

![DashCart on AWS (rotated)](build/diagrams/aws-deployment-rotated.png){#fig:aws maxheight=8.2}

**VPC.** A Virtual Private Cloud is an isolated network inside AWS. DashCart gets its own, `10.20.0.0/16`, separate from anything else in the account. It has one public subnet for the application instance and two private subnets, in two availability zones, for the databases. An internet gateway connects the public subnet to the internet. The private subnets have no route to the internet at all, so nothing outside the VPC can reach MySQL or Redis, even with a leaked password. @fig:aws-vpc shows the VPC's resource map in the AWS console.

![The VPC with its public and private subnets and route tables (AWS console)](screenshots/aws-vpc.png){#fig:aws-vpc}

**EC2.** The API and the Kafka broker run as two Docker containers on one Amazon EC2 [39] instance (@fig:aws-ec2). The instance is a `t4g.small`: two Graviton (ARM) vCPUs and 2 GiB of memory, running Amazon Linux 2023. ARM was chosen because it is cheaper than the equivalent Intel size, and because the laptop the image is built on is also ARM, so the image runs unchanged. On first boot a user-data script installs Docker and adds 1 GiB of swap as a safety margin for two JVMs in 2 GiB. The instance requires IMDSv2 for its metadata service and uses the "standard" CPU credit mode, which caps the CPU at its baseline rather than billing for bursts (Section 6.5).

![The EC2 instance running the API and Kafka (AWS console)](screenshots/aws-ec2-instance.png){#fig:aws-ec2}

**Security groups.** A security group is a stateful firewall attached to a resource [36]. The database rules allow traffic from *another security group* rather than from an IP address, so only the application instance can connect to MySQL and Redis, whatever its address (@tbl:sg, @fig:aws-sg-db and @fig:aws-sg-app). SSH is open only to the developer's own IP address, and only while deploying.

Table: Security group rules (inbound) {#tbl:sg widths=24,12,30,34}
| Security group | Port | Source | Purpose |
|---|---|---|---|
| dashcart-app-sg | 80 | 0.0.0.0/0 | API traffic and Razorpay webhooks |
| dashcart-app-sg | 22 | the developer's IP /32 | Copying the image and starting containers |
| dashcart-db-sg | 3306 | dashcart-app-sg | MySQL, from the application only |
| dashcart-cache-sg | 6379 | dashcart-app-sg | Valkey, from the application only |

![Security group of the database: MySQL only from the application's group (AWS console)](screenshots/aws-sg-db.png){#fig:aws-sg-db}

![Security group of the application: HTTP from anywhere, SSH from the developer only (AWS console)](screenshots/aws-sg-app.png){#fig:aws-sg-app}

**RDS.** Amazon RDS for MySQL [37] runs MySQL 8.4 as a managed service: AWS installs, patches and monitors it and can take backups (@fig:aws-rds). The instance is a `db.t4g.micro` with 20 GB of gp3 storage in a single availability zone, in the private subnets, with public access disabled. Automated backups were turned off for this short-lived deployment so that no snapshot outlived it. Flyway created the schema and the demo data the first time the application connected, exactly as it does locally.

![The RDS MySQL instance (AWS console)](screenshots/aws-rds.png){#fig:aws-rds}

**Cache (ElastiCache).** Amazon ElastiCache [38] provides the cache as a managed service (@fig:aws-cache). DashCart uses its Valkey engine: Valkey is the open-source continuation of Redis, compatible with the same protocol and Lua scripting, and slightly cheaper on ElastiCache than Redis OSS. The node is a `cache.t4g.micro` running Valkey 9.1, a single node in the private subnets. The application's Redis client needed no change: Spring's health check reported it as Redis 7.2.4-compatible, and the Lua reservation script ran unmodified.

![The ElastiCache Valkey cache (AWS console)](screenshots/aws-elasticache.png){#fig:aws-cache}

**Kafka.** Amazon MSK, the managed Kafka service, has no free allowance and would cost more than the rest of this deployment combined. Kafka therefore runs as a single-broker container next to the API on the same instance, the same `apache/kafka` image used locally, with its heap limited to 256 MB. For a production system this is the first thing to change (Section 8.3).

**Managed infrastructure: Elastic Beanstalk.** AWS Elastic Beanstalk [35] is the managed way to run the same setup. Given a `docker-compose.yml` bundle, it provisions the EC2 instance and its security group, starts the containers, monitors their health, collects logs, and deploys new versions with rollback. A load-balanced, auto-scaling environment is then a configuration change. For this deployment I used the EC2 instance directly instead, because it was created, verified and deleted within half an hour. Fewer moving parts made it faster to set up and to remove completely, and the scripts in `deploy/aws` do the provisioning that Beanstalk would otherwise do. For a long-running service, Beanstalk (or Amazon ECS) would be the better choice, and the application would need no change, since it is already a container configured entirely through environment variables.

## 6.3 Configuration and secrets

Nothing environment-specific is built into the image. The deployment script writes an environment file on the instance (readable only by its owner) that the API container reads at start-up (@tbl:config).

Table: Production configuration {#tbl:config widths=36,64 size=10}
| Variable | Value on AWS |
|---|---|
| DB_URL | `jdbc:mysql://<RDS endpoint>:3306/dashcart` |
| DB_USERNAME, DB_PASSWORD | The RDS master user, with a password generated by the script. A long-running deployment would add a separate user with rights on the `dashcart` schema only. |
| REDIS_HOST | The ElastiCache primary endpoint |
| KAFKA_BOOTSTRAP_SERVERS | `kafka:9092`, the Kafka container on the same Docker network |
| JWT_SECRET | 256-bit random secret generated by the script |
| ADMIN_EMAIL, ADMIN_PASSWORD | First administrator, created at start-up; password generated by the script |
| JAVA_TOOL_OPTIONS | `-XX:MaxRAMPercentage=70`, with the container limited to 900 MB |
| RAZORPAY_* | Not set: the built-in fake gateway was used, as no Razorpay test keys were available |

Generated secrets are kept on the developer's machine in a git-ignored folder and never appear in the repository, the image or the logs.

## 6.4 Deployment steps

Three scripts in `deploy/aws` do the whole job, using the AWS CLI:

1. `up.sh` creates the VPC, internet gateway, subnets and route table, the three security groups, the RDS instance and the ElastiCache cache, a key pair, and the EC2 instance. RDS and ElastiCache take several minutes to start, so the script requests them and carries on without waiting.
2. `deploy-app.sh` waits for the instance's first-boot setup, copies the locally built image to it over SSH (`docker save | docker load`, so no container registry is needed), waits for RDS and ElastiCache, writes the environment file with their endpoints, starts the Kafka and API containers, and waits until `/actuator/health` answers on the public address.
3. `down.sh` deletes everything, in dependency order: the instance, the database (without a final snapshot), the cache, the subnet groups, the security groups, the subnets, the route table, the internet gateway, the VPC and the key pair. It finds resources by name and tag, so it works even if the local state is lost, and `status.sh` lists anything still running.

@tbl:timeline shows the real run.

Table: Deployment timeline on 30 October 2026 (IST) {#tbl:timeline widths=18,82}
| Time | Event |
|---|---|
| 11:56:18 | `up.sh` starts: VPC, subnets and security groups created within 25 seconds |
| 11:57:05 | EC2 instance running; RDS and ElastiCache still being created |
| 12:00:01 | Instance set-up finished; image copy starts |
| 12:01:30 | RDS available (about 5 minutes after it was requested) |
| 12:02:19 | ElastiCache available; containers start |
| 12:03:17 | `/actuator/health` reports UP on the public address: **7 minutes from nothing to a running system** |
| 12:22:21 | Verification finished; `down.sh` starts |
| 12:26:57 | Everything deleted; `status.sh` reports nothing left |

## 6.5 Verification

**Health.** The health endpoint on the public address reported the application and both managed services as UP (@lst:aws-health).

Listing: Health check from the internet {#lst:aws-health}
```json
GET http://52.66.163.185/actuator/health
{"status": "UP",
 "components": {
   "db":    {"status": "UP",
             "details": {"database": "MySQL", "validationQuery": "isValid()"}},
   "redis": {"status": "UP", "details": {"version": "7.2.4"}},
   "diskSpace": {"status": "UP"}, "livenessState": {"status": "UP"},
   "readinessState": {"status": "UP"}, "ping": {"status": "UP"}}}
```

**A customer journey.** @lst:aws-smoke is the transcript of a customer journey run against the public address. It found the store for a pincode, registered and logged in, added to the cart, checked out (the stock was reserved with the conditional update), and started a payment. The order-created event travelled through the Kafka container to the notifications inbox within a few seconds.

Listing: Customer journey against the AWS deployment (abridged) {#lst:aws-smoke}
```text
GET  /stores/serviceable?pincode=560034
     -> {"pincode":"560034","storeId":2,"storeName":"DashCart Koramangala"}
POST /auth/register
     -> HTTP 201
POST /auth/login
     -> token eyJhbGciOiJIUzI1NiJ9...
PUT  /cart/items/15
     -> {"storeId":2,"itemCount":2,"totalAmount":318.0,"savings":42.0}
POST /orders/checkout
     -> {"id":1,"status":"CREATED","totalAmount":318.0,
         "reservationStrategy":"CONDITIONAL_UPDATE"}
POST /orders/1/payment
     -> {"razorpayOrderId":"order_fake_3606...","amountPaise":31800,
         "currency":"INR","razorpayKeyId":"rzp_fake"}
GET  /notifications
     -> [{"orderId":1,"status":"CREATED",
          "message":"Order #1 placed. Pay within 10 minutes to confirm it."}]
```

**A flash sale on AWS.** The k6 rush of Section 5.8 was run against the deployment from the developer's laptop, scaled to 100 buyers and 25 units (@tbl:aws-rush). Exactly 25 orders were accepted and the other 75 buyers were turned away. MySQL on RDS ended with 0 units available and 25 reserved, so the oversell protection held on managed infrastructure exactly as in the tests. The response times are much higher than on the laptop and are not a performance result. The instance ran in the standard CPU credit mode, capped at its baseline of 20% of each vCPU (about 0.4 of a core) to rule out any surplus charges, and every request crossed the internet between Bengaluru and Mumbai.

Table: Flash sale against the AWS deployment (CONDITIONAL_UPDATE, one run) {#tbl:aws-rush widths=14,14,10,12,22,14,14}
| Orders | Rejected | Errors | Oversold | Available / reserved | Median ms | p95 ms |
|---|---|---|---|---|---|---|
| 25 | 75 | 0 | 0 | 0 / 25 | 3,950 | 5,082 |

**Memory.** @tbl:aws-memory shows that both JVMs fitted comfortably in the 2 GiB instance, well inside their container limits, without touching swap.

Table: Memory on the EC2 instance after the tests {#tbl:aws-memory widths=40,30,30}
| Process | Used | Limit |
|---|---|---|
| dashcart-app container | 485 MiB | 900 MiB |
| kafka container | 345 MiB | 640 MiB |
| Whole instance | 1,061 MiB | 1,840 MiB (swap unused) |

## 6.6 Cost

The AWS account used for this deployment had no free-tier allowance. Since July 2025, new AWS accounts receive credits instead of the old 12-month free tier, and this account's credits were already used, so every resource was billed at on-demand rates. @tbl:cost lists the Mumbai prices of each part.

Table: On-demand prices in ap-south-1 (October 2026) {#tbl:cost widths=46,27,27}
| Resource | Price | Per hour |
|---|---|---|
| EC2 t4g.small | $0.0112 per hour | $0.0112 |
| Public IPv4 address | $0.005 per hour | $0.0050 |
| EBS gp3, 10 GB (instance disk) | $0.0912 per GB-month | $0.0012 |
| RDS db.t4g.micro, MySQL, single-AZ | $0.021 per hour | $0.0210 |
| RDS gp3 storage, 20 GB | $0.131 per GB-month | $0.0036 |
| ElastiCache cache.t4g.micro, Valkey | $0.016 per hour | $0.0160 |
| **Total** | | **≈ $0.058 per hour** |

Left running, this setup would cost about $42 a month. The verification session lasted about thirty minutes, so even if every resource were billed for a full hour, it cost less than $0.06, about five rupees. Being able to create the whole environment in seven minutes and delete it completely with one script is what made such a short, cheap deployment practical.

A production deployment would add components with a fixed monthly cost that this one deliberately avoided: an Application Load Balancer with an HTTPS certificate from AWS Certificate Manager in front of two or more instances, Multi-AZ RDS, a replicated cache, and managed Kafka or a cheaper queue. Section 8.3 discusses these trade-offs.
