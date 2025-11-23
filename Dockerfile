# Multi-stage build for Spring Boot application
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

# Copy pom.xml and download dependencies (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
# Use a slim JRE base to reduce image size. If this specific slim tag
# isn't available in your registry, we can switch to a distroless or
# buildpack-based image as a fallback.
# Currently not available:
# FROM eclipse-temurin:25-jre-jammy-slim
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

# Create non-root user
RUN groupadd -r spring && useradd -r -g spring spring

# Copy JAR from build stage
COPY --from=build /app/target/*.jar app.jar

# Change ownership
RUN chown spring:spring app.jar

# Switch to non-root user
USER spring:spring

# Expose port
EXPOSE 9010

# Health check (optional - requires Spring Boot Actuator)
# To enable, add spring-boot-starter-actuator to pom.xml and uncomment:
# HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
#   CMD java -jar app.jar --spring.actuator.health.enabled=true || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

