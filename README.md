# zarlania-api

[![CI](https://github.com/Zarlania/zarlania-api/actions/workflows/ci.yml/badge.svg)](https://github.com/Zarlania/zarlania-api/actions/workflows/ci.yml)
[![CodeQL](https://github.com/Zarlania/zarlania-api/actions/workflows/codeql.yml/badge.svg)](https://github.com/Zarlania/zarlania-api/actions/workflows/codeql.yml)
[![Gitleaks](https://github.com/Zarlania/zarlania-api/actions/workflows/gitleaks.yml/badge.svg)](https://github.com/Zarlania/zarlania-api/actions/workflows/gitleaks.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Open-source API and backend services for building and managing collections with Zarlania.

> **Status: early scaffolding.** The service currently exposes a single hello-world
> endpoint. The domain model, persistence layer and authentication are not built yet.

The browser client lives in a separate repository: [Zarlania/zarlania-app](https://github.com/Zarlania/zarlania-app).

## Requirements

| Tool   | Version | Notes                                              |
| ------ | ------- | -------------------------------------------------- |
| JDK    | 25      | Temurin recommended. Maven is supplied by `./mvnw`. |
| Docker | 24+     | Only needed for the Compose workflow.               |

## Quick start

```bash
git clone https://github.com/Zarlania/zarlania-api.git
cd zarlania-api
./mvnw spring-boot:run
```

The API listens on <http://localhost:8080>.

```bash
curl http://localhost:8080/api/hello
# {"message":"Hello from Zarlania!"}
```

### With Docker Compose

```bash
docker compose up --build
```

## Endpoints

| Method | Path                | Description                     |
| ------ | ------------------- | ------------------------------- |
| `GET`  | `/api/hello`        | Returns a greeting as JSON.     |
| `GET`  | `/actuator/health`  | Health probe used by Render.    |

## Common tasks

| Command                  | What it does                                          |
| ------------------------ | ----------------------------------------------------- |
| `./mvnw verify`          | Compiles, runs tests, and fails on formatting issues. |
| `./mvnw test`            | Runs the tests only.                                  |
| `./mvnw spotless:apply`  | Reformats the code to Google Java Style.              |
| `./mvnw spring-boot:run` | Runs the service locally with live reload.            |
| `docker compose up`      | Runs the service in a container.                      |

## Configuration

Configuration lives in `src/main/resources/application.yml`. Every value can be
overridden with an environment variable.

| Variable                         | Default                 | Description                                   |
| -------------------------------- | ----------------------- | --------------------------------------------- |
| `PORT`                           | `8080`                  | HTTP port. Render sets this automatically.    |
| `SPRING_PROFILES_ACTIVE`         | —                       | Active Spring profile.                        |
| `ZARLANIA_CORS_ALLOWED_ORIGINS`  | `http://localhost:5173` | Origins permitted to call the API.            |
| `JAVA_OPTS`                      | —                       | Extra JVM flags passed to the container.      |

## Project layout

```
src/main/java/com/zarlania/api/
  ZarlaniaApiApplication.java   Spring Boot entry point
  hello/HelloController.java    Hello-world endpoint
src/main/resources/
  application.yml               Configuration
src/test/java/                  Tests, mirroring the main package structure
```

## Contributing

Contributions are welcome. **Every change needs a tracking issue**, a branch named
`<issue-number>-<slug>`, and a pull request titled `#<issue-number> <type>: <description>`.
CI enforces this. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow.

- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)
- [Support](SUPPORT.md)

## Releases and deployment

Merging to `master` automatically publishes a GitHub Release and deploys to Render.
The version bump comes from the merged pull request's `major`, `minor` or `patch`
label. Release notes serve as the changelog — this repository has no `CHANGELOG.md`.

Deployment is configured as code in [`render.yaml`](render.yaml).

## License

[MIT](LICENSE) © Zarlania
