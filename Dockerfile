# syntax=docker/dockerfile:1.7
# Multi-stage build shared by all services: the build stage is identical for every service,
# so BuildKit builds the Maven reactor once and every service image reuses it.
#   docker build --build-arg MODULE=auth-service -t edupulse/auth-service .

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY common/pom.xml common/
COPY discovery-server/pom.xml discovery-server/
COPY api-gateway/pom.xml api-gateway/
COPY auth-service/pom.xml auth-service/
COPY ingestion-service/pom.xml ingestion-service/
COPY compliance-service/pom.xml compliance-service/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline -DexcludeArtifactIds=common || true
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
ARG MODULE
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --home /app app \
    && mkdir -p /app /data/uploads && chown -R app:app /app /data
WORKDIR /app
COPY --from=build /build/${MODULE}/target/${MODULE}-*.jar /app/app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
