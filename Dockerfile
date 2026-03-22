# =============================================================
#  Multi-stage Dockerfile — secure-login-app
#  Build context: project root (same folder as pom.xml)
#
#  Stage 1: Maven build with Java 21
#  Stage 2: Minimal JRE runtime, non-root user
# =============================================================

# ── Stage 1: Build ────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom first — Maven dependency layer is cached independently
# of source changes, so rebuilds after code edits are fast.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy the entire src/ directory (same level as pom.xml in the repo)
COPY src ./src

# Package — produces target/secure-login-app.jar (finalName in pom.xml)
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

# Non-root user — never run as root inside a container
RUN groupadd --system appgroup && \
    useradd  --system --gid appgroup appuser

WORKDIR /app

COPY --from=builder /app/target/secure-login-app.jar app.jar

RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

# All sensitive config is injected at runtime via env vars.
# No secrets are baked into this image.
ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=prod", \
  "-jar", "app.jar"]
