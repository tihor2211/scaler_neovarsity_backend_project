# DashCart API image.
#   docker build -t dashcart .
#   docker compose --profile app up -d --build      (whole stack locally)
#
# Tests are not run here: they start their own containers (Testcontainers), so run ./mvnw verify
# on the host before building the image.

# --- Build: compile and split the jar into layers ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Dependencies change less often than code, so resolve them in their own layer.
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B package -DskipTests \
    && cp target/dashcart-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# --- Run: JRE only, non-root ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 --no-create-home dashcart
WORKDIR /app

# Least to most frequently changed, so a code change only rebuilds the last layer.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER dashcart
EXPOSE 8080
# Size the heap from the container's memory limit rather than the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
