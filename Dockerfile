# Multi-stage build for Spring Boot application using layered JARs

# Build stage: compile and package the app
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

# Copy pom.xml and download dependencies (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage: use the original JAR file
# Docker will cache this layer when dependencies don't change, providing similar benefits to layered extraction
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

# Create non-root user
RUN groupadd -r spring && useradd -r -g spring spring

# Copy the JAR file from build stage
# This is a separate layer, so Docker can cache it when dependencies don't change
COPY --from=build /app/target/*.jar app.jar

# Fix ownership and switch to non-root user
RUN chown -R spring:spring /app
USER spring:spring

# Expose port
EXPOSE 9010

# Health check (optional - requires Spring Boot Actuator)
# To enable, add spring-boot-starter-actuator to pom.xml and uncomment:
# HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
#   CMD java -jar app.jar --spring.actuator.health.enabled=true || exit 1

# Run the Spring Boot application using the JAR file
ENTRYPOINT ["java", "-jar", "app.jar"]

