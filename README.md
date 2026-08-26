# Sysfoo — Pride Dashboard

Sysfoo is a Spring Boot web app that combines a live **system/infra dashboard**
with a small **team task manager** and a **community posts board**, sitting
behind simple session-based authentication. It's built as a DevSecOps practice
project — CI/CD, containerization, and database flexibility are first-class
concerns alongside the app features themselves.

## What it does

- **System dashboard** — hostname, IP address, whether it's running in Docker
  or Kubernetes, app version, and live database connectivity status.
- **Task manager** — create, edit, complete, filter, sort, and bulk-manage
  tasks (priority, completion, and deletion all persist server-side), with
  optional email notifications when a task is assigned.
- **The Watering Hole** — a lightweight posts/announcements board where
  signed-in users can share updates (title, body, optional image).
- **Authentication** — a dedicated `login.html` page (separate from the
  dashboard) handles sign-in and registration. `index.html` checks for a
  session on load and redirects to `login.html` if there isn't one; signing
  in redirects back to the dashboard.

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 17, Spring Boot 3.2.5 (Web, Data JPA, Security, Mail) |
| Database | SQLite (default/dev), PostgreSQL (prod), H2 (test) — switchable via Spring profiles |
| Auth | Spring Security 6, session cookies, BCrypt password hashing |
| Frontend | Single-page `index.html` — vanilla JS + hand-rolled CSS (no build step, no framework) |
| Packaging | Executable WAR (runs standalone via `java -jar`, or deployable to an external Tomcat) — multi-stage Docker build → `eclipse-temurin` |

## Project structure

```
src/main/java/com/example/sysfoo/
├── SysfooApplication.java        # entry point; also a SpringBootServletInitializer (dual jar/war)
├── controller/                   # REST endpoints
│   ├── AuthController.java       #   /api/auth/*  (register, login, logout, me)
│   ├── PostController.java       #   /api/posts   (list, create)
│   ├── TodoController.java       #   /todos       (list, create, update, delete)
│   ├── NotificationController.java #  /api/notify (email notifications)
│   └── SystemInfoController.java #   /system-info, /version, /database-info
├── model/                        # JPA entities: User, Post, Todo
├── repository/                   # Spring Data JPA repositories
├── security/                     # SecurityConfig, CustomUserDetailsService
└── service/                      # business logic

src/main/resources/
├── application*.properties       # per-profile config (default/prod/test)
└── static/
    ├── login.html                 # dedicated sign-in / registration page
    └── index.html                 # the dashboard (requires a session)
```

## Running it locally

```bash
# Default profile — SQLite, zero setup, DB file at ./data/sysfoo.db
mvn spring-boot:run

# Production profile — PostgreSQL
export SPRING_PROFILES_ACTIVE=prod
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/sysfoo
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgres
mvn spring-boot:run
```

Then open **http://localhost:8080** — the dashboard checks for a session and
immediately redirects you to `login.html` if you don't have one yet; sign up
from there and you're dropped straight onto the dashboard afterward.
Tables are created automatically on
startup (`hibernate.ddl-auto=update`) — no manual migration step needed.

### Running the tests

```bash
mvn clean test
```

### Docker

```bash
docker build -t sysfoo .
docker run -p 8080:8080 sysfoo    # runs with SQLite by default
```

### Running the packaged artifact (jar-style or on a real Tomcat)

`mvn package` builds a single WAR that works two ways:

```bash
# 1) Standalone, embedded Tomcat — exactly like running a plain executable jar
java -jar target/sysfoo-0.0.1-SNAPSHOT.war

# with the prod profile:
SPRING_PROFILES_ACTIVE=prod SPRING_DATASOURCE_PASSWORD=your-password \
  java -jar target/sysfoo-0.0.1-SNAPSHOT.war
```

```bash
# 2) Deploy to an external/standalone Tomcat server instead. IMPORTANT: rename
# it to ROOT.war first — the frontend calls absolute paths like /todos and
# /api/auth/me, which assume the app is served at the root context "/". A
# WAR named sysfoo.war would be served under /sysfoo/* by Tomcat's default
# naming convention, and every one of those calls would 404.
cp target/sysfoo-0.0.1-SNAPSHOT.war $CATALINA_HOME/webapps/ROOT.war
# Environment variables (SPRING_PROFILES_ACTIVE, SPRING_DATASOURCE_PASSWORD,
# etc.) go in $CATALINA_BASE/bin/setenv.sh so Tomcat's startup script picks
# them up — a plain shell `export` won't reach a Tomcat process unless it's
# launched from that same shell.
```

## API overview

| Endpoint | Method | Auth required | Purpose |
|---|---|---|---|
| `/api/auth/register` | POST | — | Create an account (auto signs in) |
| `/api/auth/login` | POST | — | Sign in |
| `/api/auth/logout` | POST | ✔ | Sign out |
| `/api/auth/me` | GET | — | Current session's user, if any |
| `/todos` | GET | — | List tasks |
| `/todos` | POST | ✔ | Create a task |
| `/todos/{id}` | PATCH | ✔ | Edit a task's text/priority/done state |
| `/todos/{id}` | DELETE | ✔ | Delete a task |
| `/api/posts` | GET | — | List posts |
| `/api/posts` | POST | ✔ | Create a post |
| `/api/notify` | POST | ✔ | Send an email notification for a task |
| `/system-info`, `/version`, `/database-info` | GET | — | Dashboard data |
| `/actuator/health` | GET | — | Container/orchestrator health probe |

## Known trade-offs

- **The login gate is client-side.** `index.html` redirects to `login.html`
  when `GET /api/auth/me` comes back unauthenticated, but the underlying
  `GET /todos` and `GET /api/posts` endpoints are still public at the HTTP
  layer (see `SecurityConfig.java`) — a direct API call can still read them
  without a session. That's an accepted trade-off for this project's scope;
  a stricter deployment would require authentication on those GETs too.
- **CSRF protection is disabled.** Acceptable for a same-origin, cookie-based
  practice app, but should be re-enabled (or replaced with token-based auth)
  before any real production use — see the comment in `SecurityConfig.java`.
- **No database migration tool** (Flyway/Liquibase) — schema is managed by
  Hibernate's `ddl-auto=update`, which is fine for a small project but not
  ideal for a team working against a shared production database.

## Bug-fix pass (build, deploy, and app-logic issues)

A prior version of this project had several bugs that kept it from building
and deploying cleanly. Each is called out with a `BUG FIX:` comment at the
point it was fixed; summary:

- **`./mvnw` couldn't run at all** — `.mvn/wrapper/maven-wrapper.properties`
  was missing, so every Maven Wrapper invocation (including whatever a CI
  pipeline runs for the test/build stage) failed before touching any code.
- **The Docker health check always failed** — the `HEALTHCHECK` in the
  `Dockerfile` polls `/actuator/health`, but `spring-boot-starter-actuator`
  wasn't a dependency and the endpoint wasn't permitted in `SecurityConfig`.
  Containers/pods would be marked unhealthy indefinitely.
- **Hardcoded fallback prod DB password** — `application-prod.properties` had
  `${SPRING_DATASOURCE_PASSWORD:postgres}`; the default is now removed so a
  missing secret fails loudly instead of silently using a weak password.
- **Task priority/completion/deletion didn't persist** — the `Todo` entity had
  no `priority`, `done`, or `createdAt` columns, and there was no update or
  delete endpoint at all. Picking a priority, completing a task, and
  deleting/clearing tasks all only changed the browser's local state and
  reverted on the next page load.
- **`/version` didn't match its own test's intent** — the controller read a
  separate, duplicate `@Value("${app.version}")` field instead of
  `SystemInfoService.getAppVersion()`, so the existing test's mock wasn't
  actually being exercised. A third, entirely unused `AppProperties`
  `@ConfigurationProperties` class bound to the same property was removed too.
- **Added dual jar/war packaging** — `pom.xml`/`SysfooApplication.java` now
  produce a single WAR that runs standalone via `java -jar` (same as the old
  plain jar) or deploys to an external Tomcat server. See "Running the
  packaged artifact" above for the `ROOT.war` context-path note.
