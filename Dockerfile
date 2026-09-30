# syntax=docker/dockerfile:1

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /build

# Resolve dependencies in their own layer so source-only changes do not re-download them.
# Best effort: anything missing here is fetched again by `package` below.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline || echo "pre-fetch incomplete, package will resolve the rest"

COPY src ./src

# Build the shaded jar: it carries jackson, hivemq/netty and logback inside, so the run
# stage needs nothing but a JRE. Pass --build-arg SKIP_TESTS=false to run the test suite.
ARG SKIP_TESTS=true
RUN mvn -B -ntp -DskipTests=${SKIP_TESTS} package \
 && cp target/p1mqtt.jar /build/p1mqtt.jar

# ---------- run ----------
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="p1mqtt" \
      org.opencontainers.image.description="P1 meter to Home Assistant MQTT bridge"

# ca-certificates for mqtt.ssl=true, curl for the healthcheck.
RUN apk add --no-cache ca-certificates curl \
 && addgroup -S p1mqtt \
 && adduser -S -D -G p1mqtt -h /opt/p1mqtt p1mqtt

WORKDIR /opt/p1mqtt
COPY --from=build /build/p1mqtt.jar ./p1mqtt.jar
USER p1mqtt

# The only socket this process listens on. The MQTT connection is outbound to your broker,
# so the broker port (mqtt.port, 1883 by default) is deliberately not exposed here.
EXPOSE 8267

# /health answers 200 as long as the HTTP endpoint is up; "degraded" broker state is
# reported in its JSON body and does not mark the container unhealthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:8267/health" > /dev/null || exit 1

# Exec form: java is PID 1, so SIGTERM from `docker stop` runs the shutdown hook.
# Appended arguments are passed on, e.g. `docker run --rm p1mqtt:1.0.0 --help`.
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/p1mqtt/p1mqtt.jar"]
