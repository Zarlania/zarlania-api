# Phase 4 — App Shell & Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the POC into a minimal but production-shaped service: Actuator health/info, springdoc OpenAPI (public Swagger UI), an env-driven CORS allowlist (closing the permissive-CORS security issue #4), the build version surfaced at `/actuator/info`, plus deployment-as-code (`render.yaml`, `docker-compose.yml`, hardened `Dockerfile`) — recorded in a new ADR.

**Architecture:** Spring Boot 4.1 (Spring Framework 7, Jackson 3) on Java 25. Actuator exposes only `health` + `info` over HTTP; springdoc auto-generates `/v3/api-docs` + `/swagger-ui.html`; CORS origins come from configuration (defaulting to the real frontend origins, overridable per environment). `render.yaml` codifies the Render web service (health check `/actuator/health`); `docker-compose.yml` runs it locally. The runtime/deploy/CORS/actuator decisions are captured in the next-numbered ADR (ADR-0002).

**Tech Stack:** Spring Boot 4.1.0, Java 25, springdoc-openapi 3.0.x (Boot 4 line), Maven, Docker, Render.

**Process:** Feature branch off `master`, tied to a GitHub issue, merged via PR. Branch protection is active — CI (build, lint+ADR tests, secrets, governance) must pass and the maintainer must approve. `master` deploys to prod, so changes must keep the service healthy.

**Spec:** `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md` (§1, §5 deploy parts; the spec predates the Boot 3→4 bump — see "Deviations" below).

---

## Scope & decisions (locked with maintainer)

- **Scope = app shell + deploy only.** Release automation (`bump-version`, `release.yml`, label validation) is a **separate later plan**.
- **ADR numbering:** only ADR-0001 exists. The ADR created here is the **next sequential number, ADR-0002** (do not hardcode — `./scripts/adr new` assigns it). The retroactive stack ADR (Java 25 / Boot 4 / Maven) is deferred to a later "seed ADRs" phase.
- **Swagger UI is public** in production (public OSS API; no secrets in the schema).
- **Deviations from the spec (intentional, Boot-4-driven):** the spec said Spring Boot 3.5.x + springdoc 2.8.x. The repo is now on **Boot 4.1.0** (merged via Dependabot, GA & supported) so this plan uses **springdoc-openapi 3.0.x** (the Boot 4 line). The Boot 4 adoption rationale will be recorded in the later stack ADR.

---

## Important context for the implementer

- Current source: `HelloController` (`GET /` → JSON hello), `WebConfig` (**permissive** `allowedOrigins("*")` — to be replaced), `ZarlaniaApiApplication`, `application.properties` (`spring.application.name`, `server.port=${PORT:8080}`). Tests: `HelloControllerTest`, `CorsConfigTest` (asserts the permissive header — must be rewritten), `ZarlaniaApiApplicationTests`.
- `pom.xml`: parent Spring Boot **4.1.0**, Java 25, deps `spring-boot-starter-web`, `spring-boot-starter-test`, `spring-boot-starter-webmvc-test`. Quality plugins: spotless, checkstyle (10.x→ may be bumped), spotbugs+findsecbugs (with `config/spotbugs/spotbugs-exclude.xml` containing the **PERMISSIVE_CORS** suppression to be removed), jacoco 0.8.15 (line+branch ≥80%, excludes `ZarlaniaApiApplication`).
- All gates run via `./mvnw verify`; fast checks via `./scripts/check`. New Java must pass google-java-format/Checkstyle; new markdown/yaml must pass markdownlint/yamllint; coverage must stay ≥80% line+branch.
- ADR tooling: `./scripts/adr new|accept|index|check`, tags registry `docs/adrs/_tags.md`.
- Issue #4 tracks the CORS hardening; this plan closes it.

---

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` | Add `spring-boot-starter-actuator`, springdoc dep; add `build-info` execution. |
| `src/main/resources/application.properties` | Actuator exposure, info/build, springdoc, CORS origins default. |
| `src/main/java/com/zarlania/api/CorsProperties.java` | `@ConfigurationProperties` record for the CORS allowlist. |
| `src/main/java/com/zarlania/api/WebConfig.java` | CORS mapping from `CorsProperties` (replaces permissive config). |
| `src/test/java/com/zarlania/api/CorsConfigTest.java` | Rewritten: allowed vs disallowed origin behavior. |
| `src/test/java/com/zarlania/api/ActuatorTest.java` | `/actuator/health` + `/actuator/info` (version) tests. |
| `src/test/java/com/zarlania/api/OpenApiTest.java` | `/v3/api-docs` available. |
| `config/spotbugs/spotbugs-exclude.xml` | Remove the PERMISSIVE_CORS suppression. |
| `render.yaml` | Render Blueprint (web service, Docker, health check, CORS env). |
| `docker-compose.yml` | Local dev runtime. |
| `Dockerfile` | Wildcard jar copy + non-root user. |
| `docs/adrs/_tags.md` | Add `deployment`, `security`, `configuration` tags. |
| `docs/adrs/0002-*.md` | ADR-0002: runtime config & deployment topology. |
| `docs/adrs/README.md` | Regenerated ADR index. |
| `CLAUDE.md` / `README.md` | Update status + commands (actuator/swagger/compose). |

---

## Task 0: Issue + branch

- [ ] **Step 1: Create the issue**

```bash
gh issue create \
  --title "Phase 4: App shell & deployment (actuator, springdoc, CORS hardening, render.yaml, docker-compose)" \
  --body "Implements Phase 4 of the repo-shell spec (§1, §5 deploy): Actuator health/info, springdoc OpenAPI (public Swagger UI), env-driven CORS allowlist (closes #4), build version at /actuator/info, render.yaml + docker-compose + hardened Dockerfile, and ADR-0002 documenting runtime/deploy/CORS/actuator. Uses springdoc 3.0.x for Spring Boot 4."
```

Note the issue number (`<ISSUE#>`).

- [ ] **Step 2: Branch + commit this plan onto it**

```bash
git switch -c "feat/<ISSUE#>-app-shell-deploy"
git add docs/superpowers/plans/2026-06-15-phase4-app-shell-deploy.md
git commit -m "docs: add Phase 4 (app shell & deploy) implementation plan (#<ISSUE#>)"
```

---

## Task 1: Actuator (health + info only)

**Files:** `pom.xml`, `src/main/resources/application.properties`, `src/test/java/com/zarlania/api/ActuatorTest.java`.

- [ ] **Step 1: Add the actuator starter to `pom.xml`** (in `<dependencies>`, after `spring-boot-starter-web`):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator</artifactId>
		</dependency>
```

- [ ] **Step 2: Configure exposure in `application.properties`** (append):

```properties
# Actuator: expose only health and info over HTTP; never leak health details publicly.
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=never
management.info.build.enabled=true
management.info.java.enabled=true
```

- [ ] **Step 3: Write the failing test** `src/test/java/com/zarlania/api/ActuatorTest.java`

```java
package com.zarlania.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class ActuatorTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc() {
    return MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void healthIsUp() throws Exception {
    mockMvc()
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
```

> Note: `@SpringBootTest` + `webAppContextSetup` loads the full context so Actuator endpoints are present (a `@WebMvcTest` slice would not register them). Keep imports minimal — Spotless `removeUnusedImports` + Checkstyle will reject unused imports.

- [ ] **Step 4: Run it**

Run: `./mvnw -q -Dtest=ActuatorTest test`
Expected: FAIL first if actuator not yet on classpath/exposed; after Steps 1–2, PASS.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/test/java/com/zarlania/api/ActuatorTest.java
git commit -m "feat: add Actuator with health+info exposed (#<ISSUE#>)"
```

---

## Task 2: Build version at `/actuator/info`

**Files:** `pom.xml`, `src/test/java/com/zarlania/api/ActuatorTest.java`.

- [ ] **Step 1: Add the `build-info` execution to the Spring Boot Maven plugin** in `pom.xml`. Change the existing `spring-boot-maven-plugin` entry to include an execution:

```xml
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
				<executions>
					<execution>
						<goals>
							<goal>build-info</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
```

- [ ] **Step 2: Add a test for the version in `/actuator/info`** — append to `ActuatorTest.java`:

```java
  @Test
  void infoExposesBuildVersion() throws Exception {
    mockMvc()
        .perform(get("/actuator/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.build.version").exists());
  }
```

- [ ] **Step 3: Run it**

Run: `./mvnw -q -Dtest=ActuatorTest test`
Expected: PASS (build-info generates `META-INF/build-info.properties`; the info endpoint exposes `build.version`). If `$.build` is absent, confirm `management.info.build.enabled=true` and that `build-info` ran (it binds to the `package`/test build via the plugin goal).

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/test/java/com/zarlania/api/ActuatorTest.java
git commit -m "feat: surface build version at /actuator/info via build-info (#<ISSUE#>)"
```

---

## Task 3: springdoc OpenAPI + Swagger UI (public)

**Files:** `pom.xml`, `src/main/resources/application.properties`, `src/test/java/com/zarlania/api/OpenApiTest.java`.

- [ ] **Step 1: Add springdoc dependency to `pom.xml`** (in `<dependencies>`):

```xml
		<dependency>
			<groupId>org.springdoc</groupId>
			<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
			<version>3.0.3</version>
		</dependency>
```

> Use the latest `3.0.x` (Spring Boot 4 line). If 3.0.3 is unavailable or incompatible with the exact Boot 4.1 patch, bump to the newest 3.0.x and note it in the commit.

- [ ] **Step 2: Configure springdoc in `application.properties`** (append):

```properties
# OpenAPI / Swagger UI (public).
springdoc.api-docs.path=/v3/api-docs
springdoc.swagger-ui.path=/swagger-ui.html
```

- [ ] **Step 3: Write the failing test** `src/test/java/com/zarlania/api/OpenApiTest.java`

```java
package com.zarlania.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class OpenApiTest {

  @Autowired private WebApplicationContext context;

  @Test
  void apiDocsAreAvailable() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openapi").exists());
  }
}
```

- [ ] **Step 4: Run it**

Run: `./mvnw -q -Dtest=OpenApiTest test`
Expected: PASS (springdoc serves `/v3/api-docs`).

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/test/java/com/zarlania/api/OpenApiTest.java
git commit -m "feat: add springdoc OpenAPI 3.x with public Swagger UI (#<ISSUE#>)"
```

---

## Task 4: CORS allowlist (closes #4) + remove permissive config & suppression

**Files:** `CorsProperties.java`, `WebConfig.java`, `application.properties`, `CorsConfigTest.java`, `config/spotbugs/spotbugs-exclude.xml`.

- [ ] **Step 1: Create `src/main/java/com/zarlania/api/CorsProperties.java`**

```java
package com.zarlania.api;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configurable CORS allowlist. Bound from {@code zarlania.cors.*}. */
@ConfigurationProperties(prefix = "zarlania.cors")
public record CorsProperties(List<String> allowedOrigins) {}
```

- [ ] **Step 2: Replace `WebConfig.java`** with the allowlist version

```java
package com.zarlania.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for browser clients. Allowed origins are an explicit allowlist sourced from
 * {@code zarlania.cors.allowed-origins} (overridable per environment), never a wildcard.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class WebConfig implements WebMvcConfigurer {

  private final CorsProperties cors;

  WebConfig(CorsProperties cors) {
    this.cors = cors;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/**")
        .allowedOrigins(cors.allowedOrigins().toArray(String[]::new))
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*");
  }
}
```

- [ ] **Step 3: Default origins in `application.properties`** (append)

```properties
# CORS allowlist (override per environment, e.g. ZARLANIA_CORS_ALLOWED_ORIGINS in Render/compose).
zarlania.cors.allowed-origins=https://zarlania.com,https://www.zarlania.com
```

- [ ] **Step 4: Rewrite `src/test/java/com/zarlania/api/CorsConfigTest.java`**

```java
package com.zarlania.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
    properties = "zarlania.cors.allowed-origins=https://zarlania.com")
class CorsConfigTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc() {
    return MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void allowedOriginGetsCorsHeader() throws Exception {
    mockMvc()
        .perform(get("/").header("Origin", "https://zarlania.com"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://zarlania.com"));
  }

  @Test
  void disallowedOriginIsRejected() throws Exception {
    mockMvc()
        .perform(
            options("/")
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  void allowedOriginPreflightSucceeds() throws Exception {
    mockMvc()
        .perform(
            options("/")
                .header("Origin", "https://zarlania.com")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://zarlania.com"));
  }
}
```

- [ ] **Step 5: Run the CORS tests**

Run: `./mvnw -q -Dtest=CorsConfigTest test`
Expected: PASS. (Spring returns 403 for a disallowed preflight origin; the allowed origin echoes back in `Access-Control-Allow-Origin`.)

- [ ] **Step 6: Remove the PERMISSIVE_CORS suppression** from `config/spotbugs/spotbugs-exclude.xml` — delete the `<Match>` block for `WebConfig` / `PERMISSIVE_CORS` (the comment referencing issue #4 and the surrounding `<Match>`…`</Match>`). Leave the `ZarlaniaApiApplication` and `SPRING_ENDPOINT` matches.

- [ ] **Step 7: Verify SpotBugs is still clean without the suppression**

Run: `./mvnw -q clean compile test-compile spotbugs:check`
Expected: PASS (the allowlist config no longer triggers `PERMISSIVE_CORS`). If SpotBugs unexpectedly flags the new config, investigate the finding rather than re-adding a blanket suppression.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/zarlania/api/CorsProperties.java src/main/java/com/zarlania/api/WebConfig.java src/main/resources/application.properties src/test/java/com/zarlania/api/CorsConfigTest.java config/spotbugs/spotbugs-exclude.xml
git commit -m "feat: replace permissive CORS with env-driven allowlist; closes #4 (#<ISSUE#>)"
```

---

## Task 5: `render.yaml` (Render Blueprint)

**Files:** Create `render.yaml`.

- [ ] **Step 1: Create `render.yaml`**

```yaml
services:
  - type: web
    name: zarlania-api
    runtime: docker
    dockerfilePath: ./Dockerfile
    plan: free
    region: oregon
    branch: master
    healthCheckPath: /actuator/health
    autoDeploy: true
    envVars:
      - key: ZARLANIA_CORS_ALLOWED_ORIGINS
        value: https://zarlania.com,https://www.zarlania.com
```

> `region` is set to `oregon` as a placeholder — change it to the existing service's region. Render injects `PORT` automatically (the app already reads `${PORT:8080}`). Adopting this Blueprint, or simply changing the existing service's health check path to `/actuator/health` in the dashboard, is covered in the PR's Render steps (Task 10).

- [ ] **Step 2: Commit**

```bash
git add render.yaml
git commit -m "build: add render.yaml Blueprint (health check /actuator/health, CORS env) (#<ISSUE#>)"
```

---

## Task 6: `docker-compose.yml` (local dev)

**Files:** Create `docker-compose.yml`.

- [ ] **Step 1: Create `docker-compose.yml`**

```yaml
services:
  api:
    build: .
    ports:
      - "8080:8080"
    environment:
      PORT: "8080"
      ZARLANIA_CORS_ALLOWED_ORIGINS: "http://localhost:4200,http://localhost:8080"
```

- [ ] **Step 2: Commit**

```bash
git add docker-compose.yml
git commit -m "build: add docker-compose for local dev (#<ISSUE#>)"
```

---

## Task 7: Harden the Dockerfile

**Files:** `Dockerfile`.

- [ ] **Step 1: Replace `Dockerfile`** (version-agnostic jar copy + non-root runtime user)

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
RUN useradd --system --no-create-home --uid 10001 appuser
COPY --from=build /app/target/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Verify the image builds and the app is healthy**

```bash
docker build -t zarlania-api:phase4 .
docker run --rm -e PORT=8080 -p 8080:8080 zarlania-api:phase4 &
sleep 25 && curl -fsS http://localhost:8080/actuator/health && echo
curl -fsS http://localhost:8080/ && echo
docker stop "$(docker ps -q --filter ancestor=zarlania-api:phase4)" 2>/dev/null || true
```
Expected: `/actuator/health` returns `{"status":"UP"}` and `/` returns the hello JSON. If `docker` is unavailable in the environment, skip the run and rely on CI/Render; note it.

- [ ] **Step 3: Commit**

```bash
git add Dockerfile
git commit -m "build: harden Dockerfile (wildcard jar, non-root user) (#<ISSUE#>)"
```

---

## Task 8: ADR-0002 (runtime config & deployment topology)

**Files:** `docs/adrs/_tags.md`, `docs/adrs/0002-*.md`, `docs/adrs/README.md`.

- [ ] **Step 1: Register new tags**

```bash
./scripts/adr add-tag deployment --description "Deployment topology, hosting, and release/runtime infrastructure"
./scripts/adr add-tag security --description "Security model and controls (CORS, secrets, auth, exposure)"
./scripts/adr add-tag configuration --description "Runtime configuration and application properties"
```

- [ ] **Step 2: Create the ADR (next number = 0002)**

```bash
./scripts/adr new \
  --name "Service runtime configuration and deployment topology" \
  --tags deployment,security,configuration --author stimothy
```

This creates `docs/adrs/0002-service-runtime-configuration-and-deployment-topology.md` as `proposed`.

- [ ] **Step 3: Fill in the ADR body** — set `description` (in frontmatter) to `Defines Actuator exposure, public OpenAPI docs, the CORS allowlist model, and Render/Docker deployment topology.` and replace the body sections (from `## Context and Problem Statement` onward) with:

```markdown
## Context and Problem Statement

The POC exposed a single endpoint with permissive CORS and no health/observability or
deploy-as-code. As the first production-shaped iteration we need: a health signal for
Render, machine-readable API docs, a safe CORS policy for the browser frontend, and the
deployment captured in the repo. This is a live, public service, so the runtime posture
must be secure by default.

## Decision Drivers

- Render needs a health check endpoint; we want minimal, non-leaky observability.
- The browser frontend (zarlania.com) must call the API without exposing it to all origins.
- API consumers need discoverable, accurate docs.
- Deployment should be reproducible and reviewed, not click-ops only.

## Considered Options

- Actuator `health`+`info` only vs. exposing more endpoints.
- CORS allowlist (config-driven) vs. permissive wildcard vs. a gateway.
- springdoc OpenAPI vs. hand-written docs vs. none.
- `render.yaml` Blueprint vs. dashboard-only configuration.

## Decision Outcome

1. **Actuator**: depend on `spring-boot-starter-actuator`; expose only `health` and `info`
   over HTTP (`management.endpoints.web.exposure.include=health,info`), with
   `health.show-details=never`. Render's health check path is `/actuator/health`.
2. **Version**: the Spring Boot `build-info` goal publishes the build version to
   `/actuator/info` (`build.version`), sourced from the pom version.
3. **API docs**: springdoc-openapi (3.x, Spring Boot 4 line) serves `/v3/api-docs` and a
   **public** `/swagger-ui.html`.
4. **CORS**: an explicit allowlist bound from `zarlania.cors.allowed-origins`
   (overridable via `ZARLANIA_CORS_ALLOWED_ORIGINS`), never a wildcard. Defaults to the
   production frontend origins; local dev adds localhost via env. This replaces the POC's
   permissive config and resolves the `PERMISSIVE_CORS` finding (issue #4).
5. **Deployment as code**: `render.yaml` codifies the Render web service (Docker runtime,
   free tier, health check, CORS env); `docker-compose.yml` runs the service locally; the
   `Dockerfile` runs as a non-root user with a version-agnostic jar copy.

### Consequences

- Good: secure-by-default CORS; reproducible deploy config; health + version visibility;
  discoverable docs.
- Bad: a public Swagger UI is extra surface (accepted for an OSS API); free-tier Render
  still cold-starts; CORS origins must be kept in sync with the frontend.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md`
- Issue #4 (CORS hardening)
```

- [ ] **Step 4: Regenerate index and validate**

```bash
./scripts/adr index
./scripts/adr check
```
Expected: `ADR check passed`. (Leave the ADR `proposed`; the maintainer accepts it — see Task 10.)

- [ ] **Step 5: Commit**

```bash
git add docs/adrs/
git commit -m "docs: add ADR-0002 (runtime config & deployment topology) (#<ISSUE#>)"
```

---

## Task 9: Docs refresh + full verification

**Files:** `README.md`, `CLAUDE.md`.

- [ ] **Step 1: Update `README.md`** — add a "Running & endpoints" section after the ADR section:

```markdown
## Running locally

```bash
docker compose up --build      # serves on http://localhost:8080
```

Key endpoints: `GET /` (hello), `GET /actuator/health`, `GET /actuator/info` (version),
`GET /swagger-ui.html` (API docs), `GET /v3/api-docs` (OpenAPI JSON).
```

- [ ] **Step 2: Update `CLAUDE.md` status line** — change the Phase 1 status note to:

```markdown
Phases 1–4 are in place (ADRs, quality gates, CI/governance, and the app shell:
Actuator, OpenAPI, CORS allowlist, deploy config). Release automation and the seed
ADRs are still pending (see `docs/superpowers/`).
```

- [ ] **Step 3: Full local verification**

```bash
./scripts/check --full
```
Expected: pre-commit all green; `./mvnw clean verify` BUILD SUCCESS with Spotless, Checkstyle, SpotBugs+FindSecBugs, all tests, and JaCoCo (line+branch ≥80%) passing. If coverage dips below 80% from the new config classes, add a focused test (e.g., assert `CorsProperties` binding) or, only if a class is genuinely untestable boilerplate, add a narrow jacoco exclude with justification.

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: document app endpoints and update phase status (#<ISSUE#>)"
```

---

## Task 10: PR, CI, acceptance, and Render steps

- [ ] **Step 1: Push + open PR**

```bash
git push -u origin "feat/<ISSUE#>-app-shell-deploy"
gh pr create \
  --title "Phase 4: App shell & deployment (#<ISSUE#>)" \
  --body "$(cat <<'EOF'
Implements Phase 4: Actuator (health+info), springdoc OpenAPI 3.x (public Swagger UI),
env-driven CORS allowlist (closes #4, removes the PERMISSIVE_CORS suppression), build
version at /actuator/info, render.yaml + docker-compose + hardened Dockerfile, and ADR-0002
documenting the runtime/deploy/CORS/actuator decisions.

Note: uses springdoc 3.0.x because the repo is on Spring Boot 4.1 (the Boot 3→4 adoption
rationale will be recorded in the later stack ADR).

Closes #<ISSUE#>
Closes #4

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2: Watch CI**

```bash
gh pr checks --watch
```
Expected: all required checks pass. Fix any failures and re-push.

- [ ] **Step 3: Maintainer accepts ADR-0002** (acceptance is the maintainer's call). Once they approve, set it accepted before merge:

```bash
./scripts/adr accept 0002 && ./scripts/adr index && ./scripts/adr check
git add docs/adrs/ && git commit -m "docs: accept ADR-0002 (#<ISSUE#>)" && git push
```

- [ ] **Step 4: Render configuration (maintainer, post-merge)** — document in the PR:
  1. In the Render dashboard for the `zarlania-api` service, set **Health Check Path** to `/actuator/health` (was `/`).
  2. Add env var **`ZARLANIA_CORS_ALLOWED_ORIGINS`** = `https://zarlania.com,https://www.zarlania.com` (adjust to the real frontend origins).
  3. Optionally adopt `render.yaml` as a Blueprint (New → Blueprint → connect the repo) to manage the service as code; otherwise the in-repo `render.yaml` is documentation/IaC reference.
  4. After deploy, verify `https://api.zarlania.com/actuator/health` returns `{"status":"UP"}` and `https://api.zarlania.com/swagger-ui.html` loads.

---

## Out of scope (later)

- Release automation: `bump-version`, `release.yml`, in-PR version-bump/label validation (next plan).
- Retroactive seed ADRs for the stack (Java 25/Boot 4/Maven), quality gates, and workflow.
- Business logic, persistence, authentication.
- Upgrading off Render free tier (cold starts remain).
