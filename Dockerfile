# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Copy the wrapper and POM first so dependency resolution is cached independently
# of source changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
# Spotless is skipped here: formatting is enforced in CI, not in the image build.
RUN ./mvnw -B -ntp -DskipTests -Dspotless.check.skip=true package \
    && mv target/*.jar app.jar

# ---------- Runtime stage ----------
FROM eclipse-temurin:25-jre-alpine AS runtime

# Nothing supplies this today: Render builds from source and cannot receive
# build arguments from a deploy hook, so production images are labelled
# 0.0.0-dev. Pass --build-arg to stamp a real version on a locally built image.
ARG APP_VERSION=0.0.0-dev
ENV APP_VERSION=${APP_VERSION}

LABEL org.opencontainers.image.title="zarlania-api" \
      org.opencontainers.image.description="Open-source API and backend services for Zarlania" \
      org.opencontainers.image.source="https://github.com/Zarlania/zarlania-api" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${APP_VERSION}"

# Run as an unprivileged user.
RUN addgroup -S zarlania && adduser -S zarlania -G zarlania
WORKDIR /app
COPY --from=build --chown=zarlania:zarlania /workspace/app.jar app.jar
USER zarlania

# Render overrides PORT at runtime; 8080 is the local default.
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
