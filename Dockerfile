# One Dockerfile for all services (multi-stage):
#   docker build --build-arg MODULE=auth-service -t edupulse/auth-service .
# Stage 1 builds the Maven project, stage 2 runs only the jar of one service on a small Java 21 image.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system app && mkdir -p /data/uploads && chown app /data/uploads
WORKDIR /app
COPY --from=build /build/${MODULE}/target/${MODULE}-*.jar app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
