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

# Extract stage: extract the layered jar produced by Spring Boot
FROM eclipse-temurin:25-jre-jammy AS extract
WORKDIR /workspace
COPY --from=build /app/target/*.jar app.jar

# Use Spring Boot layertools to extract layers into separate folders
RUN java -Djarmode=layertools -jar app.jar extract

# Runtime stage: copy extracted layers into the final image (cacheable layers)
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

# Create non-root user
RUN groupadd -r spring && useradd -r -g spring spring

# Copy layered parts (each will become a separate Docker layer)
COPY --from=extract /workspace/spring-boot-loader/ ./spring-boot-loader/
COPY --from=extract /workspace/dependencies/ ./dependencies/
COPY --from=extract /workspace/snapshot-dependencies/ ./snapshot-dependencies/
COPY --from=extract /workspace/application/ ./application/

# Fix ownership and switch to non-root user
RUN chown -R spring:spring /app
USER spring:spring

# Expose port
EXPOSE 9010

# Health check (optional - requires Spring Boot Actuator)
# To enable, add spring-boot-starter-actuator to pom.xml and uncomment:
# HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
#   CMD java -jar app.jar --spring.actuator.health.enabled=true || exit 1

# Run using the Spring Boot JarLauncher with a classpath including the extracted layers
ENTRYPOINT ["java", "-cp", "/app/spring-boot-loader/*:/app/dependencies/*:/app/snapshot-dependencies/*:/app/application/*", "org.springframework.boot.loader.JarLauncher"]

