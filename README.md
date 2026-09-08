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

## Second pass — cleanup, a container config bug, and a real escaping bug

- **`AppProperties.java` was still shipped** even though the note above says
  it was removed — it was dead code (nothing referenced it) and contradicted
  its own changelog entry. Deleted.
- **Orphaned `static/css/style.css`** — an old stylesheet from an earlier
  design iteration, not linked from either `index.html` or `login.html`
  (both use their own embedded `<style>`). Deleted to stop it misleading the
  next person who edits the CSS and wonders why their changes do nothing.
- **The container silently ran SQLite without its documented safety
  settings.** `application-default.properties` sets
  `?journal_mode=WAL&busy_timeout=30000` on the datasource URL specifically
  to avoid `SQLITE_BUSY` locking errors, but the `Dockerfile`'s
  `SPRING_DATASOURCE_URL` env var was a bare `jdbc:sqlite:/data/sysfoo.db` —
  an env var override replaces the *whole* property value, so every
  container run quietly lost that protection. Fixed by carrying the same
  query params through.
- **A real escaping bug in two places in `index.html`.** `escapeAttr()`
  correctly protects a plain HTML attribute, but it was also being used to
  embed values inside a *nested* single-quoted string — `style="background-
  image:url('...')"` for post images, and `onclick="copyToClipboard('...')"`
  for the system-info copy buttons. Browsers HTML-decode an attribute's text
  *before* treating it as CSS/JS, so an escaped `&#39;` decodes back to a
  literal `'` and breaks out of the inner string — any post image URL
  containing an apostrophe corrupted the card's styling. Fixed by moving
  both values into `data-*` attributes and setting them via the CSSOM /
  reading them in a delegated click handler, which never re-parses the
  string as code.
- **A redundant duplicate network call** — `fetchSystemInfo()` fetched both
  `/system-info` and `/version` and wrote the same value into the same
  `#app-version` element from both, on every load and every 30-second
  auto-refresh tick. Removed the second call.
- **A more thorough `.dockerignore` existed but was never active** — it had
  been saved as a file literally named `dockerignore`, missing the leading
  dot, so Docker never read it; the shorter, older `.dockerignore` was the
  one silently in effect. Consolidated into the real `.dockerignore` and
  removed the dead file.

### UI/UX enhancements

- **Accent theme picker** (Savanna Gold / Twilight Violet / Jungle Teal) in
  the header — swaps only the brand-accent CSS variables so the base
  contrast never regresses, persisted to `localStorage`, and mirrored on
  `login.html` so the two pages stay in sync.
- **Live relative timestamps** ("5m ago") on tasks and posts, refreshing
  every 20s, with the absolute time still available as a tooltip on posts.
- **The task list and Watering Hole board now actually refresh live.** The
  30s auto-refresh cycle and the "Refresh" button previously only touched
  system-info/database-info, even though both surfaces are described as
  shared/team data — a teammate's new task or post never showed up without a
  manual reload. It's now merged in place rather than replaced wholesale,
  and a task currently being edited is left completely untouched (checked
  both before and after the network round trip) so a poll can never
  overwrite unsaved keystrokes.
- **Accessibility**: the toast container announces itself
  (`role="status" aria-live="polite"`), and the filter/priority buttons now
  expose `aria-pressed` state.
- **Keyboard shortcuts**: `/` focuses the task search box, `Escape` clears
  it — mirrored in the search box's placeholder text.

## Third pass — real user assignment, task privacy, comments & file uploads

This pass changes what kind of app this is in one important way: tasks are
no longer a fully shared team list — **a task is now only visible to the
person who created it and the person it's assigned to.** Everything else
below builds on that.

- **Version bug fixed properly.** `app.version` in `application.properties`
  was a hand-typed string with no connection to `pom.xml`'s own `<version>`
  — two numbers to remember to keep in sync, and they'd drifted
  (`0.0.1-SNAPSHOT` vs `1.0.0`). It's now `@project.version@`, filled in by
  Maven at build time from `pom.xml` — bump the version in exactly one
  place.
- **User directory + profile.** `GET /api/users` lists every registered
  account (username, display name, email) for the assignee picker; `GET
  /api/users/me/profile` backs a new profile panel (click your name in the
  header) showing your email, member-since date, and task counts.
- **Real assignees, not free text.** `Todo.assigneeUsername` now references
  an actual account, validated server-side. The "Assigned To" field in the
  Task Manager is a dropdown of real users (including yourself), and the
  notification email is *always* looked up from that account — never typed
  by hand. That also closes an abuse vector the old free-text email field
  had: previously anyone could type any address at all into the notify
  field and the backend would happily email it.
- **Task visibility.** `GET /todos` now requires login and returns only
  tasks you created or are assigned to (previously public to anyone,
  authenticated or not). Editing is scoped the same way; deleting is
  creator-only. A task you're not part of 404s rather than 403s, so its
  existence isn't confirmed to someone outside it.
- **Comments** on a task — `GET`/`POST /todos/{id}/comments` — visible to
  and postable by the creator and the assignee.
- **Task attachments** — `.txt`/`.zip` only, 20MB max, `GET`/`POST
  /todos/{id}/attachments`. Stored on disk under a fresh random filename
  (never the user-supplied name) specifically to close off path traversal;
  the original filename is kept only for display.
- **Post uploads.** `POST /api/posts` moved from a JSON body to
  `multipart/form-data`: an image URL, an uploaded image, and a separate
  general file attachment are all optional and independent. An uploaded
  image takes priority over a pasted URL if a post somehow has both.
  `GET /api/files/{id}` serves any attachment back — public for a post's
  files (the board itself is public), creator-or-assignee-only for a task's.
- **Found and fixed a real deployment bug while wiring up file storage:**
  the upload directory was configured as a path relative to the app's
  working directory, which inside the Docker image resolves *inside the
  container*, not on the mounted `/data` volume — uploaded files would have
  been silently deleted on every container restart, the same class of bug
  as the SQLite URL fix in the second pass. Fixed with the same pattern: an
  explicit absolute-path override in the Dockerfile.
- Updated the existing `TodoControllerTest` (it would have failed outright —
  the controller gained several new required dependencies) and added
  coverage for the new visibility/ownership rules.

### Known gaps from this pass

- No automated tests for the new frontend behavior (comments, attachments,
  the profile modal) — only the Java-side tests were updated.
- No UI to change *display name*; only the account owner's email is shown
  as read-only.
- The Black Duck-style "does uploaded content actually match its claimed
  type" byte-level check isn't implemented — only the file extension and
  the browser-supplied Content-Type are validated.
- A few judgment calls were made without being able to ask first — worth
  revisiting if they don't match what you had in mind: delete is
  creator-only (not assignee); both creator and assignee (not just
  assignee) can comment; the post-upload file whitelist (images + pdf/txt/
  zip/doc/docx) was invented since none was specified; and every signed-in
  user can see every other user's email via the directory, which is what
  makes the auto-fill possible but is a real privacy trade-off worth
  knowing about.

