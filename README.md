# Sysfoo Application

A DevOps Learning App built with Spring Boot.

---

## About

Sysfoo displays real-time system information, database connectivity status, and a to-do list to illustrate REST APIs, JPA/Hibernate, and frontend interaction with JavaScript.

**Default database:** SQLite (file-based, zero-setup)  
**Production database:** PostgreSQL (activated via `prod` profile)

---

## Project Structure

```
sysfoo/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/example/sysfoo/
    │   │   ├── SysfooApplication.java
    │   │   ├── config/
    │   │   │   └── AppProperties.java
    │   │   ├── controller/
    │   │   │   ├── SystemInfoController.java
    │   │   │   └── TodoController.java
    │   │   ├── model/
    │   │   │   └── Todo.java
    │   │   ├── repository/
    │   │   │   └── TodoRepository.java
    │   │   └── service/
    │   │       ├── SystemInfoService.java
    │   │       └── TodoService.java
    │   └── resources/
    │       ├── application.properties           ← sets active profile
    │       ├── application-default.properties   ← SQLite config
    │       ├── application-prod.properties      ← PostgreSQL config
    │       └── static/
    │           ├── index.html
    │           └── css/style.css
    └── test/
        └── java/com/example/sysfoo/
            ├── SysfooApplicationTests.java
            ├── controller/
            │   └── SystemInfoControllerTest.java
            └── service/
                └── TodoServiceTest.java
```

---

## Prerequisites

- JDK 17+
- Maven 3.6+

---

## Build & Run

### 1. Compile & test
```bash
mvn clean install
```

### 2. Run unit tests only
```bash
mvn clean test
```

### 3. Package (skip tests)
```bash
mvn package -DskipTests
```

### 4. Run the application
```bash
java -jar target/sysfoo-0.0.1-SNAPSHOT.jar
```

The SQLite database file is created automatically at `./data/sysfoo.db` on first launch — no setup needed.

---

## Endpoints

| URL | Description |
|-----|-------------|
| `http://localhost:8080/` | Dashboard (frontend) |
| `http://localhost:8080/system-info` | System info JSON |
| `http://localhost:8080/version` | App version |
| `http://localhost:8080/database-info` | DB connection status |
| `http://localhost:8080/todos` | To-do list (GET / POST) |

---

## Switching to PostgreSQL (Production)

Set the following environment variables and activate the `prod` profile:

```bash
export SPRING_PROFILES_ACTIVE=prod
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/sysfoo
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=secret

java -jar target/sysfoo-0.0.1-SNAPSHOT.jar
```

---

## DevOps Ideas

- **Containerize** — write a `Dockerfile`
- **Dev environment** — set up with `docker-compose`
- **CI Pipeline** — Jenkins + Docker + Git
- **Deploy** — Kubernetes + ArgoCD
- **Cloud** — automate with Terraform
- **DevSecOps** — add security scanning to the pipeline

---

## License

Apache License 2.0 — see [https://www.apache.org/licenses/LICENSE-2.0](https://www.apache.org/licenses/LICENSE-2.0)
