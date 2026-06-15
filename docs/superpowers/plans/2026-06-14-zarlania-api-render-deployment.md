# Zarlania API — Render Deployment POC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a minimal Java 25 / Spring Boot / Maven "hello world" REST API, containerize it with Docker, and deploy it to Render via GitHub so it is reachable at `https://api.zarlania.com`.

**Architecture:** A single-controller Spring Boot app reads its port from the `PORT` env var Render injects. A multi-stage Dockerfile (build with JDK 25 + Maven Wrapper, run on a JRE 25 image) produces the runtime image. Render builds the Dockerfile on every push to `master` and auto-provisions TLS for the custom domain. DNS is connected via a CNAME in Squarespace Domains.

**Tech Stack:** Java 25, Spring Boot 3.5.x, Maven (via wrapper), Docker, Render (free tier), GitHub, Squarespace Domains.

---

## File Structure

| File | Responsibility |
|------|----------------|
| `pom.xml` | Maven build config: Java 25, Spring Boot parent, web starter |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/*` | Maven Wrapper — lets the Docker build run Maven without a separate install |
| `src/main/java/com/zarlania/api/ZarlaniaApiApplication.java` | Spring Boot entrypoint |
| `src/main/java/com/zarlania/api/HelloController.java` | The single `GET /` endpoint |
| `src/main/resources/application.properties` | Binds `server.port` to `${PORT:8080}` |
| `src/test/java/com/zarlania/api/HelloControllerTest.java` | MockMvc test for the endpoint |
| `Dockerfile` | Multi-stage build → runnable image listening on `$PORT` |
| `.dockerignore` | Keeps `target/` and local cruft out of the build context |
| `.gitignore` | Standard Java/Maven ignores |

---

## Task 1: Scaffold the Spring Boot project

**Files:**
- Create: `pom.xml`
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
- Create: `src/main/java/com/zarlania/api/ZarlaniaApiApplication.java`
- Create: `src/main/resources/application.properties`
- Create: `.gitignore`

The simplest reliable scaffold is Spring Initializr. Generate, unzip into the repo, then verify.

- [ ] **Step 1: Generate the project from Spring Initializr**

Run (from the repo root `/Users/steventimothy/workspace/zarlania-api`):

```bash
curl -s https://start.spring.io/starter.tgz \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=3.5.6 \
  -d javaVersion=25 \
  -d groupId=com.zarlania \
  -d artifactId=zarlania-api \
  -d name=zarlania-api \
  -d packageName=com.zarlania.api \
  -d dependencies=web \
  | tar -xzvf -
```

Expected: extracts `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/main/java/com/zarlania/api/ZarlaniaApiApplication.java`, `src/test/...`, and a `.gitignore`.

If Initializr rejects `javaVersion=25` or `bootVersion=3.5.6`, drop to the newest values it accepts (e.g. `bootVersion=3.5.5`), then manually set `<java.version>25</java.version>` in `pom.xml` in the next step.

- [ ] **Step 2: Confirm `pom.xml` targets Java 25**

Open `pom.xml`. Ensure it contains:

```xml
<properties>
    <java.version>25</java.version>
</properties>
```

If it shows a different version, edit it to `25`.

- [ ] **Step 3: Verify the project builds**

Run:

```bash
./mvnw -q clean package -DskipTests
```

Expected: `BUILD SUCCESS` and a JAR at `target/zarlania-api-0.0.1-SNAPSHOT.jar`.

If the build fails on a Java/Spring version mismatch, lower `bootVersion` to the latest 3.5.x or 3.6.x that supports Java 25 and re-run.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Scaffold Spring Boot project (Java 25, Maven)"
```

---

## Task 2: Add the hello-world endpoint (TDD)

**Files:**
- Test: `src/test/java/com/zarlania/api/HelloControllerTest.java`
- Create: `src/main/java/com/zarlania/api/HelloController.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/zarlania/api/HelloControllerTest.java`:

```java
package com.zarlania.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HelloController.class)
class HelloControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rootReturnsHelloMessage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello from Zarlania API"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./mvnw -q test -Dtest=HelloControllerTest
```

Expected: FAIL — compilation error because `HelloController` does not exist yet.

- [ ] **Step 3: Write the minimal implementation**

Create `src/main/java/com/zarlania/api/HelloController.java`:

```java
package com.zarlania.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HelloController {

    @GetMapping("/")
    public Map<String, String> hello() {
        return Map.of("message", "Hello from Zarlania API");
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./mvnw -q test -Dtest=HelloControllerTest
```

Expected: PASS (`BUILD SUCCESS`, Tests run: 1, Failures: 0).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zarlania/api/HelloController.java src/test/java/com/zarlania/api/HelloControllerTest.java
git commit -m "Add GET / hello endpoint with MockMvc test"
```

---

## Task 3: Bind the server port to Render's `$PORT`

**Files:**
- Modify: `src/main/resources/application.properties`

Render assigns a port via the `PORT` env var and routes external traffic to it. The app must listen there.

- [ ] **Step 1: Set the port property**

Replace the contents of `src/main/resources/application.properties` with:

```properties
spring.application.name=zarlania-api
server.port=${PORT:8080}
```

- [ ] **Step 2: Verify the app runs locally on the default port**

Run:

```bash
./mvnw -q spring-boot:run &
sleep 25
curl -s http://localhost:8080/
kill %1
```

Expected: prints `{"message":"Hello from Zarlania API"}`.

- [ ] **Step 3: Verify it honors an overridden port**

Run:

```bash
PORT=9090 ./mvnw -q spring-boot:run &
sleep 25
curl -s http://localhost:9090/
kill %1
```

Expected: prints `{"message":"Hello from Zarlania API"}` on port 9090.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "Bind server.port to Render PORT env var"
```

---

## Task 4: Containerize with a multi-stage Dockerfile

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

- [ ] **Step 1: Create `.dockerignore`**

Create `.dockerignore`:

```
target/
.git/
.idea/
*.iml
.serena/
docs/
```

- [ ] **Step 2: Create the `Dockerfile`**

Create `Dockerfile`:

```dockerfile
# --- Build stage ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -q clean package -DskipTests

# --- Run stage ---
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/zarlania-api-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

If `eclipse-temurin:25-jre` is not available when building, change the run stage base image to `eclipse-temurin:25-jdk` (larger but always present).

- [ ] **Step 3: Build the image locally**

Run:

```bash
docker build -t zarlania-api .
```

Expected: build completes with `naming to docker.io/library/zarlania-api`.

If `dependency:go-offline` errors, it is safe to delete that line — the `package` step will download dependencies anyway.

- [ ] **Step 4: Run the container honoring `$PORT` and verify**

Run:

```bash
docker run --rm -e PORT=8080 -p 8080:8080 --name zarlania-test -d zarlania-api
sleep 20
curl -s http://localhost:8080/
docker stop zarlania-test
```

Expected: prints `{"message":"Hello from Zarlania API"}`.

- [ ] **Step 5: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "Add multi-stage Dockerfile for Render deployment"
```

---

## Task 5: Push to GitHub

**Files:** none (repo operations).

This task is interactive — it needs the user's GitHub account.

- [ ] **Step 1: Confirm the `gh` CLI is authenticated**

Run:

```bash
gh auth status
```

Expected: shows a logged-in GitHub account. If not, the user runs `! gh auth login` in the session and follows the prompts.

- [ ] **Step 2: Create the GitHub repo and push**

Run from the repo root:

```bash
gh repo create zarlania-api --public --source=. --remote=origin --push
```

Expected: creates `https://github.com/<user>/zarlania-api` and pushes `master`.

- [ ] **Step 3: Verify the remote has the code**

Run:

```bash
gh repo view --web
```

Expected: opens the repo in the browser showing `pom.xml`, `Dockerfile`, and `src/`.

---

## Task 6: Create the Render Web Service (guided, manual dashboard)

**Files:** none (Render dashboard). Claude guides; the user clicks.

- [ ] **Step 1: Create the service**

Instruct the user:
1. Go to <https://dashboard.render.com> and sign up / log in (signing in with GitHub is easiest).
2. Click **New +** → **Web Service**.
3. Connect the GitHub account and select the `zarlania-api` repository (grant Render access if prompted).

- [ ] **Step 2: Configure the service**

Instruct the user to set:
- **Name:** `zarlania-api`
- **Region:** closest to the user
- **Branch:** `master`
- **Runtime / Language:** **Docker** (Render auto-detects the `Dockerfile`)
- **Instance Type:** **Free**
- Leave build/start commands blank — the Dockerfile defines them.

Then click **Create Web Service** (or **Deploy Web Service**).

- [ ] **Step 3: Watch the first deploy**

Instruct the user to watch the **Logs** tab until it shows `Started ZarlaniaApiApplication` and Render reports the service as **Live**. The first Docker build takes several minutes.

- [ ] **Step 4: Verify the default Render URL**

Once live, the user copies the service URL (e.g. `https://zarlania-api.onrender.com`). Verify:

```bash
curl -s https://zarlania-api.onrender.com/
```

Expected: `{"message":"Hello from Zarlania API"}` (first hit may take ~60s if cold).

Record this `*.onrender.com` URL — it is the CNAME target for the next task.

---

## Task 7: Connect the custom domain `api.zarlania.com`

**Files:** none (Render dashboard + Squarespace Domains). Claude guides; the user clicks.

- [ ] **Step 1: Add the custom domain in Render**

Instruct the user:
1. In the Render service, go to **Settings** → **Custom Domains**.
2. Click **Add Custom Domain**, enter `api.zarlania.com`, and confirm.
3. Render shows a verification status and a **CNAME target** (typically `zarlania-api.onrender.com`). Copy that target value exactly.

- [ ] **Step 2: Add the CNAME in Squarespace Domains**

Instruct the user:
1. Go to <https://account.squarespace.com/domains>, open `zarlania.com`, then **DNS** / **DNS Settings**.
2. Add a custom record:
   - **Type:** `CNAME`
   - **Host / Name:** `api`
   - **Value / Data:** the Render CNAME target from Step 1 (e.g. `zarlania-api.onrender.com`)
   - **TTL:** default (e.g. 1 hour) is fine
3. Save. Do not add a conflicting A record for the `api` host.

- [ ] **Step 3: Wait for DNS to propagate and verify resolution**

Run (repeat until it resolves to the Render target; propagation can take minutes to ~1 hour):

```bash
dig +short api.zarlania.com CNAME
```

Expected: returns the Render target (e.g. `zarlania-api.onrender.com.`).

- [ ] **Step 4: Confirm Render verifies the domain and issues TLS**

Instruct the user to refresh the **Custom Domains** panel in Render until `api.zarlania.com` shows **Verified** and a TLS certificate is issued (green/active). This is automatic once DNS resolves.

- [ ] **Step 5: Final end-to-end verification**

Run:

```bash
curl -s https://api.zarlania.com/
```

Expected: `{"message":"Hello from Zarlania API"}` over valid HTTPS. **This is the success criterion — the POC is complete.**

---

## Task 8: Verify auto-deploy on push (optional confirmation)

**Files:**
- Modify: `src/main/java/com/zarlania/api/HelloController.java`

Confirms the GitHub → Render pipeline redeploys automatically.

- [ ] **Step 1: Make a trivial visible change**

In `HelloController.java`, change the message to `"Hello from Zarlania API v2"`.

- [ ] **Step 2: Update the test to match**

In `HelloControllerTest.java`, change the expected value to `"Hello from Zarlania API v2"`, then run:

```bash
./mvnw -q test -Dtest=HelloControllerTest
```

Expected: PASS.

- [ ] **Step 3: Commit and push**

```bash
git add src/main/java/com/zarlania/api/HelloController.java src/test/java/com/zarlania/api/HelloControllerTest.java
git commit -m "Bump hello message to v2 to verify auto-deploy"
git push
```

- [ ] **Step 4: Verify Render auto-deployed**

Watch the Render **Events/Logs** tab for a new deploy triggered by the push. Once live:

```bash
curl -s https://api.zarlania.com/
```

Expected: `{"message":"Hello from Zarlania API v2"}`. Confirms push-to-deploy works.

---

## Notes for the Implementer

- **Free-tier cold start:** After ~15 min idle the service sleeps; the next request takes ~50–90s while Java boots. Expected, not a bug.
- **Version fallbacks:** If Java 25 + Spring Boot 3.5.6 has any incompatibility, bump Spring Boot to the latest 3.5.x/3.6.x that lists Java 25 support. Keep Java at 25.
- **Docker image fallback:** If `eclipse-temurin:25-jre` is unavailable, use `eclipse-temurin:25-jdk` for the run stage.
- **Tasks 5–7 are interactive** and require the user's GitHub, Render, and Squarespace accounts. Pause and guide rather than assuming access.
