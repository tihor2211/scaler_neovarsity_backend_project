# 6. Deployment Flow

> <<DRAFT: this chapter describes the planned AWS deployment. Instance types, screenshots and the verification results will be filled in from the real deployment.>>

DashCart is deployed as a Docker container on AWS. The application runs on an EC2 instance managed by Elastic Beanstalk; MySQL and Redis are managed AWS services (RDS and ElastiCache) in private subnets of a VPC; Kafka runs as a second container next to the application. This chapter explains the container image, each AWS component and why it was chosen, and the steps of a deployment.

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
- **Memory sizing from the container.** `MaxRAMPercentage=75` sizes the Java heap from the container's memory limit rather than the host's, and `ExitOnOutOfMemoryError` makes the JVM exit, and the platform restart it, instead of limping on after running out of memory.
- **Tests run before, not inside, the build.** The integration tests start their own containers through Testcontainers, so they run on the developer machine or in CI (`./mvnw verify`) before the image is built, not inside the Docker build.

For local runs, `docker compose --profile app up -d --build` starts MySQL, Redis, Kafka and the API together. Configuration comes only from environment variables, with secrets in a `.env` file that is excluded from version control; `.env.example` documents them. Kafka is configured with two listeners: one advertised as `localhost:9092` for an application started from the IDE, and one as `kafka:29092` for the application container on the Docker network.

## 6.2 Target architecture on AWS

@fig:aws shows the planned deployment in the Mumbai region (ap-south-1), close to the Bengaluru users of the demo stores.

![Planned AWS deployment](build/diagrams/aws-deployment.png){#fig:aws}

**VPC.** A Virtual Private Cloud is an isolated network inside AWS. DashCart's VPC has public subnets for the application instance and private subnets, in two availability zones, for the databases. An internet gateway connects the public subnets to the internet. The private subnets have no route to the internet at all: nothing outside the VPC can reach the databases, even if a password leaked.

**EC2.** The application and Kafka containers run on one Amazon EC2 virtual machine [39] running Amazon Linux 2023 with Docker. <<Instance type and size, e.g. t3.small with 2 GB: confirm>>. Two gigabytes is about the minimum for the JVM of the application plus the Kafka broker on one machine.

**Security groups.** A security group is a stateful virtual firewall attached to a resource [36]. Instead of allowing traffic by IP address, DashCart's database rules allow traffic *from another security group*, so only the application instance can connect to MySQL and Redis, whatever its IP address becomes.

Table: Security group rules (inbound) {#tbl:sg widths=24,20,26,30}
| Security group | Port | Source | Purpose |
|---|---|---|---|
| dashcart-app-sg | 80 | 0.0.0.0/0 | API traffic and Razorpay webhooks |
| dashcart-app-sg | 22 | <<developer IP>>/32 only | Administration (or none, using Session Manager) |
| dashcart-db-sg | 3306 | dashcart-app-sg | MySQL, from the application only |
| dashcart-cache-sg | 6379 | dashcart-app-sg | Redis, from the application only |

**RDS.** Amazon RDS for MySQL [37] runs MySQL 8.4 as a managed service: AWS handles installation, patching, automated backups and recovery. The instance is <<db.t4g.micro, single-AZ, 20 GB storage: confirm>>, in the private subnets, with public access disabled. Flyway creates the schema the first time the application starts, exactly as it does locally.

**Cache (ElastiCache).** Amazon ElastiCache [38] provides a managed Redis-compatible cache for the listing cache, the logout deny-list and the Redis stock strategy. <<Node type and engine: confirm>>. It also lives in the private subnets.

**Kafka.** Amazon MSK, the managed Kafka service, has no free tier and costs far more than the rest of this deployment combined, so Kafka runs as a single-broker container on the application instance, the same image used locally. For a production system this is the first thing to change (Section 8.3).

**Elastic Beanstalk (managed infrastructure).** Elastic Beanstalk [35] deploys and runs the application. It takes an application bundle, here a `docker-compose.yml` describing the application and Kafka containers, stores it in S3, provisions the EC2 instance and its security group, starts the containers, watches their health and collects logs. Deploying a new version is a single `eb deploy` command, and a failed deployment can be rolled back to the previous version. A *single-instance* environment is used, with no load balancer, to stay within the free tier. Moving to a load-balanced, auto-scaling environment is a configuration change, not a code change, because the application is stateless (Section 8.3).

## 6.3 Configuration and secrets

Nothing environment-specific is built into the image. Elastic Beanstalk passes environment properties to the containers as environment variables, which Spring Boot reads directly (@tbl:config).

Table: Production configuration {#tbl:config widths=40,60 size=10}
| Variable | Value in AWS |
|---|---|
| DB_URL | `jdbc:mysql://<RDS endpoint>:3306/dashcart` |
| DB_USERNAME, DB_PASSWORD | Database user created for the application (not the RDS master user) |
| REDIS_HOST | ElastiCache primary endpoint |
| KAFKA_BOOTSTRAP_SERVERS | `kafka:29092` (the Kafka container) |
| JWT_SECRET | Random 384-bit Base64 secret, generated once |
| ADMIN_EMAIL, ADMIN_PASSWORD | First administrator, created at start-up |
| RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET | Razorpay test-mode keys and webhook secret |

Secrets never appear in the repository, the image or the application logs.

## 6.4 Deployment steps

1. Run the test suite locally (`./mvnw verify`); deploy only if it passes.
2. Create the VPC with public and private subnets in two availability zones, and the three security groups.
3. Create the RDS MySQL instance and the ElastiCache cluster in the private subnets, attached to their security groups.
4. Create the Elastic Beanstalk application and a single-instance environment on the Docker platform, in the VPC's public subnet, with the environment properties from @tbl:config.
5. Deploy the bundle (`eb deploy`). On first start the application runs the Flyway migrations, which create the schema and the demo data, and seeds the administrator.
6. Verify: `GET /actuator/health` reports MySQL and Redis UP; a smoke test registers a customer, adds to cart, checks out and starts a payment.
7. Register the webhook URL `http://<environment URL>/payments/razorpay/webhook` in the Razorpay dashboard with the webhook secret, and make a test-mode payment.

## 6.5 Verification

> <<Screenshots to add: Elastic Beanstalk environment (health OK), EC2 instance, VPC and subnets, security group rules, RDS instance, ElastiCache cluster, /actuator/health response from the public URL, a Razorpay test payment and the order marked PAID.>>

## 6.6 Cost and alternatives

The deployment is sized for the AWS free tier and credits: one small EC2 instance, one micro RDS instance, one micro cache node, and no load balancer or NAT gateway, which would each add a fixed monthly charge. <<Monthly cost estimate: confirm after deployment.>> For a real launch the main changes would be a load balancer with HTTPS (AWS Certificate Manager) in front of two or more instances, a Multi-AZ RDS instance, a replicated cache, and managed Kafka (MSK) or a hosted alternative. Elastic Container Service (ECS) on Fargate would be the natural next step if the services were split: it runs containers without managing instances at all.
