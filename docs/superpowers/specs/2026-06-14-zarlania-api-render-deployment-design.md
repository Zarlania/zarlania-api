# Zarlania API — Hello World POC on Render

**Date:** 2026-06-14
**Status:** Approved design

## Goal

Prove the full deployment pipeline end to end:

**Spring Boot app → GitHub → Render (auto-deploy) → served at `api.zarlania.com`** with DNS connected from Squarespace Domains.

The application content is intentionally trivial. The value of this project is demonstrating that the infrastructure path works out of the gate, using the same Java/Spring Boot/Maven stack the real project will use.

## Success Criteria

`curl https://api.zarlania.com/` returns a simple JSON hello message over valid HTTPS.

## Decisions

| Decision | Choice |
|----------|--------|
| Render tier | Free tier ($0/mo) |
| Java version | Java 25 (LTS) |
| Spring Boot | Latest 3.x that supports Java 25 |
| Build tool | Maven |
| Render runtime | Docker (Render has no native Java runtime) |
| Render setup | Manual dashboard setup (guided, first-time user) |

## 1. The Application

A minimal Spring Boot 3.x application (Java 25, Maven).

- One REST controller exposing `GET /` returning JSON: `{"message":"Hello from Zarlania API"}`.
- Health: use the `GET /` endpoint as Render's health check path (keeps dependencies minimal; Actuator not required for the POC).
- Standard Maven layout, builds a runnable fat JAR via `mvn package`.
- `server.port` reads from the `PORT` environment variable that Render injects, defaulting to `8080` locally: `server.port=${PORT:8080}`.

**Files:**
- `pom.xml`
- `src/main/java/com/zarlania/api/ZarlaniaApiApplication.java`
- `src/main/java/com/zarlania/api/HelloController.java`
- `src/main/resources/application.properties`

## 2. Containerization

A multi-stage `Dockerfile`:

- **Stage 1 (build):** Maven + JDK 25 image builds the fat JAR.
- **Stage 2 (run):** Slim JRE 25 image runs the JAR.
- The container listens on `$PORT` (provided by Render); Spring binds to it via the `server.port` property above.

A `.dockerignore` excludes the local `target/` and other build artifacts from the build context.

## 3. Deployment Pipeline

1. Initialize the project and push to a new **GitHub** repository.
2. In Render, create a **Web Service** (free tier), connect it to the GitHub repo, runtime = **Docker**.
3. Render auto-builds from the `Dockerfile` and redeploys automatically on every push to `master`.

## 4. Custom Domain & DNS

1. In Render: add custom domain `api.zarlania.com`. Render provides a `CNAME` target (e.g. `zarlania-api.onrender.com`).
2. In **Squarespace Domains**: add a `CNAME` DNS record with host `api` pointing to the Render target.
3. Once DNS resolves, Render auto-provisions a free TLS certificate, making `https://api.zarlania.com` live.

## 5. Known Tradeoff (Free Tier)

On the free tier the service spins down after ~15 minutes of inactivity. The first request after spin-down takes roughly 50–90 seconds while the Java runtime cold-starts. This is acceptable for a POC whose purpose is to prove connectivity, not to serve production traffic.

## Out of Scope

- Authentication, persistence, business logic.
- CI/CD beyond Render's built-in auto-deploy on push.
- render.yaml / infrastructure-as-code (chose manual dashboard setup).
- Multiple environments / staging.
