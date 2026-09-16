# syntax=docker/dockerfile:1
# Multi-stage build for Spring Boot application

# Build stage: compile and package the app
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

# Resolve only dependencies needed by this project (faster than dependency:go-offline,
# which pulls the entire Spring Boot BOM graph including unused cloud artifacts).
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests dependency:resolve dependency:resolve-plugins

# Copy source code and build; reuse the Maven cache mount so jars are not re-downloaded
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests clean package

# Runtime stage
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

# curl is used by HEALTHCHECK; create the non-root user in the same layer
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r spring && useradd -r -g spring spring

# Copy the JAR file from build stage
COPY --from=build /app/target/*.jar app.jar

# Fix ownership and switch to non-root user
RUN chown -R spring:spring /app
USER spring:spring

# Expose port
EXPOSE 9010

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:9010/health || exit 1

# Run the Spring Boot application using the JAR file
ENTRYPOINT ["java", "-jar", "app.jar"]
