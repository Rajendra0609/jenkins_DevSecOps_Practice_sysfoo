# Sysfoo — Environment Variables Reference

Every environment variable the app reads, and exactly how to set it for each
way you might run it. Values marked **required** must be set or the app
either fails to start (`prod` profile DB password) or that one feature
(email) stays disabled — everything else has a working default.

## Variable reference

| Variable | Default | Required? | Notes |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `default` | No | `default` = SQLite, `prod` = Postgres + email, `test` = H2 |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/sysfoo` (prod only) | No | Only read in `prod` profile |
| `SPRING_DATASOURCE_USERNAME` | `postgres` (prod only) | No | Only read in `prod` profile |
| `SPRING_DATASOURCE_PASSWORD` | *none* | **Yes, in `prod`** | App fails to start in `prod` without this |
| `SPRING_MAIL_HOST` | `smtp.gmail.com` (prod only) | No | |
| `SPRING_MAIL_PORT` | `587` (prod only) | No | |
| `SPRING_MAIL_USERNAME` | *empty* | For email | Your Gmail address |
| `SPRING_MAIL_PASSWORD` | *empty* | For email | The Gmail **App Password** — see below |
| `SYSFOO_MAIL_FROM` | built from `SPRING_MAIL_USERNAME` | No | Custom "From" header, e.g. `Sysfoo <alerts@yourdomain.com>` |

Without `SPRING_MAIL_USERNAME`/`SPRING_MAIL_PASSWORD` set (or outside `prod`),
`/api/notify` just returns a 503 — that's expected, not broken.

## Getting the Gmail "token" (App Password)

Gmail SMTP no longer accepts your normal account password.

1. Turn on **2-Step Verification**: myaccount.google.com/security
2. Go to **App Passwords**: myaccount.google.com/apppasswords
3. Create one named `sysfoo` → Google gives you a 16-character code (`abcd efgh ijkl mnop`)
4. Use it with the spaces removed: `SPRING_MAIL_PASSWORD=abcdefghijklmnop`

---

## 1. Maven (`mvn spring-boot:run`)

**Linux / macOS (bash/zsh):**
```bash
export SPRING_PROFILES_ACTIVE=prod
export SPRING_DATASOURCE_PASSWORD=your-postgres-password
export SPRING_MAIL_USERNAME=youraddress@gmail.com
export SPRING_MAIL_PASSWORD=abcdefghijklmnop
mvn spring-boot:run
```

**Windows (PowerShell):**
```powershell
$env:SPRING_PROFILES_ACTIVE="prod"
$env:SPRING_DATASOURCE_PASSWORD="your-postgres-password"
$env:SPRING_MAIL_USERNAME="youraddress@gmail.com"
$env:SPRING_MAIL_PASSWORD="abcdefghijklmnop"
mvn spring-boot:run
```

**One-liner (Linux/macOS only), no `export` needed:**
```bash
SPRING_PROFILES_ACTIVE=prod SPRING_DATASOURCE_PASSWORD=your-postgres-password mvn spring-boot:run
```

---

## 2. Running the packaged artifact (`java -jar ...`)

`mvn package` now produces a WAR (`sysfoo-0.0.1-SNAPSHOT.war`) — but it's
still run the exact same way as the old plain jar was, via `java -jar`. The
JVM doesn't care about the file extension, only that it's a valid
executable archive, which Spring Boot's repackaging guarantees either way.

Same idea as Section 1 — the variables just need to be in the shell
environment (or inherited from a parent process) before the JVM starts.

```bash
export SPRING_PROFILES_ACTIVE=prod
export SPRING_DATASOURCE_PASSWORD=your-postgres-password
export SPRING_MAIL_USERNAME=youraddress@gmail.com
export SPRING_MAIL_PASSWORD=abcdefghijklmnop
java -jar target/sysfoo-0.0.1-SNAPSHOT.war
```

Or inline, without exporting first:
```bash
SPRING_PROFILES_ACTIVE=prod SPRING_DATASOURCE_PASSWORD=your-postgres-password java -jar app.war
```

**Running it as a systemd service** (typical for a real Linux host) — put the
variables in an env file and reference it from the unit:

`/etc/sysfoo/sysfoo.env`:
```
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_PASSWORD=your-postgres-password
SPRING_MAIL_USERNAME=youraddress@gmail.com
SPRING_MAIL_PASSWORD=abcdefghijklmnop
```

`/etc/systemd/system/sysfoo.service`:
```ini
[Service]
EnvironmentFile=/etc/sysfoo/sysfoo.env
ExecStart=/usr/bin/java -jar /opt/sysfoo/app.war
```
Restrict the env file's permissions (`chmod 600`) since it holds secrets.

---

## 3. Docker

**Inline flags:**
```bash
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/sysfoo \
  -e SPRING_DATASOURCE_PASSWORD=your-postgres-password \
  -e SPRING_MAIL_USERNAME=youraddress@gmail.com \
  -e SPRING_MAIL_PASSWORD=abcdefghijklmnop \
  sysfoo
```

**`--env-file` (cleaner, keeps secrets out of shell history/`docker inspect` args list):**

`.env` (add this to `.gitignore` — it already is):
```
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/sysfoo
SPRING_DATASOURCE_PASSWORD=your-postgres-password
SPRING_MAIL_USERNAME=youraddress@gmail.com
SPRING_MAIL_PASSWORD=abcdefghijklmnop
```
```bash
docker run -p 8080:8080 --env-file .env sysfoo
```

Recall the image already bakes in `SPRING_PROFILES_ACTIVE=default` and
`SPRING_DATASOURCE_URL=jdbc:sqlite:/data/sysfoo.db` (see `Dockerfile`) —
anything you pass with `-e`/`--env-file` overrides those defaults.

---

## 4. Tomcat

The project now builds a single **WAR** that works two ways (see
`pom.xml` / `SysfooApplication.java`):

1. **Standalone, embedded Tomcat** — `java -jar target/sysfoo-0.0.1-SNAPSHOT.war`
   runs it exactly like a plain executable jar always did. Section 2 above
   (env vars for the JAR) applies identically here — just swap the filename.

2. **Deployed into a standalone/external Tomcat server** — copy the WAR into
   `$CATALINA_HOME/webapps/`.

   ⚠️ **Rename it to `ROOT.war` first.** The frontend calls absolute paths
   like `/todos`, `/api/auth/me`, `/actuator/health` — it assumes the app is
   served at the root context `/`. If you deploy it as `sysfoo.war`, Tomcat
   serves it under `/sysfoo/*` by its default naming convention, and every
   one of those calls 404s against the real root instead.
   ```bash
   cp target/sysfoo-0.0.1-SNAPSHOT.war $CATALINA_HOME/webapps/ROOT.war
   ```

**An external Tomcat doesn't read your shell's `export`ed variables**
automatically the way a plain Java process does — you set them in a file
Tomcat itself sources at startup:

**Linux — `$CATALINA_BASE/bin/setenv.sh`** (create it if it doesn't exist; `catalina.sh` sources it automatically):
```bash
export SPRING_PROFILES_ACTIVE=prod
export SPRING_DATASOURCE_PASSWORD=your-postgres-password
export SPRING_MAIL_USERNAME=youraddress@gmail.com
export SPRING_MAIL_PASSWORD=abcdefghijklmnop
export JAVA_OPTS="$JAVA_OPTS -Dspring.profiles.active=prod"
```
Make it executable: `chmod +x $CATALINA_BASE/bin/setenv.sh`

**Windows — `%CATALINA_BASE%\bin\setenv.bat`:**
```bat
set SPRING_PROFILES_ACTIVE=prod
set SPRING_DATASOURCE_PASSWORD=your-postgres-password
set SPRING_MAIL_USERNAME=youraddress@gmail.com
set SPRING_MAIL_PASSWORD=abcdefghijklmnop
```

**If Tomcat runs as a systemd service instead**, add the variables to the
unit file directly rather than `setenv.sh`:
```ini
[Service]
Environment="SPRING_PROFILES_ACTIVE=prod"
Environment="SPRING_DATASOURCE_PASSWORD=your-postgres-password"
Environment="SPRING_MAIL_USERNAME=youraddress@gmail.com"
Environment="SPRING_MAIL_PASSWORD=abcdefghijklmnop"
```

Either way, restart Tomcat afterward for the variables to take effect:
```bash
$CATALINA_HOME/bin/shutdown.sh && $CATALINA_HOME/bin/startup.sh
# or, under systemd:
sudo systemctl restart tomcat
```

---

## 5. Kubernetes

Split plain config from secrets. Non-sensitive values (`SPRING_PROFILES_ACTIVE`,
`SPRING_DATASOURCE_URL`, `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`) can go
straight in the Deployment; anything sensitive (`SPRING_DATASOURCE_PASSWORD`,
`SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`) belongs in a `Secret`.

**Create the secret:**
```bash
kubectl create secret generic sysfoo-secrets \
  --from-literal=SPRING_DATASOURCE_PASSWORD=your-postgres-password \
  --from-literal=SPRING_MAIL_USERNAME=youraddress@gmail.com \
  --from-literal=SPRING_MAIL_PASSWORD=abcdefghijklmnop
```

**Reference both in the Deployment manifest:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sysfoo
spec:
  replicas: 1
  selector:
    matchLabels:
      app: sysfoo
  template:
    metadata:
      labels:
        app: sysfoo
    spec:
      containers:
        - name: sysfoo
          image: sysfoo:latest
          ports:
            - containerPort: 8080
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "prod"
            - name: SPRING_DATASOURCE_URL
              value: "jdbc:postgresql://postgres-service:5432/sysfoo"
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: sysfoo-secrets
                  key: SPRING_DATASOURCE_PASSWORD
            - name: SPRING_MAIL_USERNAME
              valueFrom:
                secretKeyRef:
                  name: sysfoo-secrets
                  key: SPRING_MAIL_USERNAME
            - name: SPRING_MAIL_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: sysfoo-secrets
                  key: SPRING_MAIL_PASSWORD
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
```

The `livenessProbe` above works now that `/actuator/health` actually exists
and is publicly reachable (see the bug-fix pass) — same endpoint the Docker
`HEALTHCHECK` uses.

**After changing a Secret**, Kubernetes does *not* automatically restart pods
that already read it. Roll them yourself:
```bash
kubectl rollout restart deployment/sysfoo
```
