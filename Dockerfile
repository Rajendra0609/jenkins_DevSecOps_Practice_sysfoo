FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Rajendra0609" \
      app="sysfoo" \
      description="SysFoo Spring Boot application with SQLite"

# Create non-root user
RUN addgroup -S sysfoo && adduser -S sysfoo -G sysfoo

# Install sqlite (needed for sqlite-jdbc)
RUN apk add --no-cache sqlite

# Create directories
RUN mkdir -p /app /data && chown -R sysfoo:sysfoo /app /data

WORKDIR /app

# 👇 Copy already-built JAR from Jenkins workspace
COPY target/sysfoo-0.0.1-SNAPSHOT.jar app.jar

RUN chown sysfoo:sysfoo app.jar

USER sysfoo

VOLUME ["/data"]

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    SPRING_PROFILES_ACTIVE=default \
    SPRING_DATASOURCE_URL=jdbc:sqlite:/data/sysfoo.db

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
