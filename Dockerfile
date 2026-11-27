# syntax=docker/dockerfile:1.7

# Stage 1: Build fat jar natively on the host architecture (pure Java bytecode)
FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests \
    && cp target/muse-any-connect-*.jar /build/app.jar

# Stage 2: Minimal JRE runtime (multi-arch: linux/amd64 & linux/arm64)
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN addgroup -S muse && adduser -S muse -G muse

COPY --from=builder --chown=muse:muse /build/app.jar /app/app.jar

ENV SERVER_PORT=8080 \
    JAVA_OPTS="-Xms32m -Xmx128m"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider "http://127.0.0.1:${SERVER_PORT:-8080}/" || exit 1

USER muse

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
