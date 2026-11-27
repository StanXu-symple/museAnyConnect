# syntax=docker/dockerfile:1.7

# Stage 1: Build fat jar natively on the host architecture (pure Java bytecode)
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17-noble AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests \
    && cp target/muse-any-connect-*.jar /build/app.jar

# Stage 2: Minimal JRE runtime (multi-arch: linux/amd64 & linux/arm64)
FROM eclipse-temurin:17-jre-noble

WORKDIR /app

RUN groupadd --system --gid 10001 muse \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin muse \
    && mkdir -p /data/sqlite /data/logs \
    && chown -R muse:muse /app /data

COPY --from=builder --chown=muse:muse /build/app.jar /app/app.jar
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh

ENV SERVER_PORT=8080 \
    MUSE_DATA_DIR=/data \
    JAVA_OPTS="-Xms32m -Xmx128m"

EXPOSE 8080

VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT:-8080}/health" >/dev/null || exit 1

ENTRYPOINT ["/app/docker-entrypoint.sh"]
