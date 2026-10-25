# syntax=docker/dockerfile:1
#
# core-api image. The build context is the REPOSITORY ROOT (the OpenAPI contract and the shared JSON
# schemas live outside services/core-api):
#
#   docker build -f services/core-api/Dockerfile -t proprio/core-api .
#
# Stage 1 compiles with Gradle on the UBI OpenJDK 21 image; stage 2 is the JRE-only UBI runtime,
# running as a non-root user (uid 185).

FROM registry.access.redhat.com/ubi9/openjdk-21:latest AS build
USER root
WORKDIR /build
# Inputs outside the service directory (resolved by build.gradle.kts as ../../contracts, ../../protocol).
COPY contracts/ contracts/
COPY protocol/schema/ protocol/schema/
COPY services/core-api/ services/core-api/
RUN chown -R 185:0 /build
USER 185
WORKDIR /build/services/core-api
# The jar is tested in CI (./gradlew build); the image build only packages it.
RUN --mount=type=cache,target=/home/default/.gradle,uid=185 \
    sh ./gradlew --no-daemon --console=plain bootJar -x test -x spotlessCheck -x spotlessJava \
    && cp build/libs/core-api.jar /tmp/core-api.jar

FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest
LABEL org.opencontainers.image.title="proprio core-api" \
      org.opencontainers.image.description="Proprio core REST API (Spring Boot)"
COPY --from=build --chown=185:0 /tmp/core-api.jar /deployments/core-api.jar
USER 185
ENV SPRING_PROFILES_ACTIVE="" \
    PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# /health is public and returns 503 only when the database is down.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD curl --fail --silent --max-time 2 "http://localhost:${PORT}/health" || exit 1
ENTRYPOINT ["java", "-jar", "/deployments/core-api.jar"]
