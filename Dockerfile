# ─────────────────────────────────────────────────────────────────
# Stage 1 — Build
# ─────────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy dependency manifest first — layer-cached unless pom.xml changes
COPY pom.xml .
RUN mvn dependency:go-offline -B --quiet

# Copy source and build the deployable artifact (skip tests — already run in CI)
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
# SQLite DB file will live at /data/sysfoo.db, uploaded files under
# /data/uploads — both survive restarts via the VOLUME below.
RUN mkdir -p /app /data/uploads && chown -R sysfoo:sysfoo /app /data

WORKDIR /app

# ── Copy the executable WAR from builder ──────────────────────────
# UPDATE: pom.xml now builds a WAR (see pom.xml / SysfooApplication.java for
# the dual jar/war packaging change). Spring Boot's repackaged WAR is still a
# self-contained executable — `java -jar app.war` runs it exactly the way
# `java -jar app.jar` did before. This image always runs standalone; it never
# deploys into an external Tomcat.
COPY --from=builder /build/target/sysfoo-*.war app.war
RUN chown sysfoo:sysfoo app.war

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
# BUG FIX: this used to override SPRING_DATASOURCE_URL with a bare
# "jdbc:sqlite:/data/sysfoo.db" — since an env var replaces the entire
# property value (not just the path), that silently dropped the
# journal_mode=WAL&busy_timeout=30000 query params that
# application-default.properties documents as required to avoid SQLITE_BUSY
# locking errors. Every container run was missing the exact concurrency
# safeguard the default profile was written to provide.
ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    SPRING_PROFILES_ACTIVE=default \
    SPRING_DATASOURCE_URL=jdbc:sqlite:/data/sysfoo.db?journal_mode=WAL&busy_timeout=30000 \
    APP_UPLOAD_DIR=/data/uploads

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.war"]
