# ─────────────────────────────────────────────────────────────────
# Stage 1 — Build
# ─────────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy dependency manifest first — layer-cached unless pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline -B --quiet

# Copy source and build the fat JAR (skip tests — already run in CI)
COPY src ./src
RUN mvn package -DskipTests -B --quiet

# ─────────────────────────────────────────────────────────────────
# Stage 2 — Runtime
# ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# ── Labels ───────────────────────────────────────────────────────
LABEL maintainer="Rajendra0609" \
      app="sysfoo" \
      description="SysFoo Spring Boot application with SQLite"

# ── Non-root user for security ────────────────────────────────────
RUN addgroup -S sysfoo && adduser -S sysfoo -G sysfoo

# ── SQLite native library (required by sqlite-jdbc on Alpine) ─────
RUN apk add --no-cache sqlite

# ── App directory & persistent data volume ───────────────────────
# SQLite DB file will live at /data/sysfoo.db  (survives restarts)
RUN mkdir -p /app /data && chown -R sysfoo:sysfoo /app /data

WORKDIR /app

# ── Copy the fat JAR from builder ─────────────────────────────────
COPY --from=builder /build/target/sysfoo-*.jar app.jar
RUN chown sysfoo:sysfoo app.jar

# ── Switch to non-root ─────────────────────────────────────────────
USER sysfoo

# ── Volume for SQLite database persistence ────────────────────────
VOLUME ["/data"]

# ── Expose Spring Boot default port ──────────────────────────────
EXPOSE 8080

# ── Health check ─────────────────────────────────────────────────
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# ── JVM tuning + activate default profile (SQLite) ───────────────
ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    SPRING_PROFILES_ACTIVE=default \
    SPRING_DATASOURCE_URL=jdbc:sqlite:/data/sysfoo.db

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
