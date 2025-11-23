# Docker Setup Guide

This guide explains how to run the chat application using Docker.

Ref: Cursor AI

All commands should be run in the root folder

## Prerequisites

- Docker Engine 20.10+
- Docker Compose 2.0+

## Quick Start

1. **Copy environment file (optional):**

   ```bash
   cp .env.example .env
   # Edit .env if you want to customize passwords/ports
   ```

2. **Build and start all services:**

   ```bash
   docker-compose up -d
   ```

3. **View logs:**

   ```bash
   docker-compose logs -f app
   ```

4. **Stop all services:**
   ```bash
   docker-compose down
   ```

## Services

The Docker Compose setup includes:

- **postgres**: PostgreSQL 18 database (port 5432)
- **redis**: Redis 7 for sessions and cache (port 6379)
- **rabbitmq**: RabbitMQ with management UI (ports 5672, 15672)
- **app-1**: Spring Boot application (port 9010)
- **app-2**: Second app instance for multi-instance testing (port 9011, optional)

## Accessing Services

### Application

- Main app: http://localhost:9010
- Second instance: http://localhost:9011 (if enabled)

### RabbitMQ Management UI

- URL: http://localhost:15672
- Username: `guest` (default)
- Password: `guest` (default)

### Database

```bash
# Connect to PostgreSQL
docker exec -it chat-app-postgres psql -U postgres -d chatdb
```

### Redis CLI

```bash
# Connect to Redis
docker exec -it chat-app-redis redis-cli -a redis123
```

## Multi-Instance Setup

To run multiple app instances:

```bash
docker-compose --profile multi-instance up -d
```

This starts:

- `app-1` on port 9010
- `app-2` on port 9011

Both instances share the same database, Redis, and RabbitMQ.

## Environment Variables

Key environment variables (can be set in `.env` file):

| Variable            | Description         | Default      |
| ------------------- | ------------------- | ------------ |
| `POSTGRES_PASSWORD` | PostgreSQL password | `5555`       |
| `REDIS_PASSWORD`    | Redis password      | `redis123`   |
| `RABBITMQ_USER`     | RabbitMQ username   | `guest`      |
| `RABBITMQ_PASSWORD` | RabbitMQ password   | `guest`      |
| `INSTANCE_ID`       | App instance ID     | `instance-1` |
| `LOG_LEVEL`         | Logging level       | `INFO`       |
| `JPA_SHOW_SQL`      | Show SQL queries    | `false`      |

## How Spring Boot Maps Environment Variables

Spring Boot automatically converts environment variable names to property paths using these rules:

1. Convert to lowercase
2. Replace underscores (`_`) with dots (`.`)
3. Map to the corresponding YAML property

### Example: `SPRING_APPLICATION_INSTANCE_ID`

```
Environment Variable:  SPRING_APPLICATION_INSTANCE_ID
                          ↓
Spring Boot converts:  spring.application.instance-id
                          ↓
Maps to YAML:          spring:
                         application:
                           instance-id: <value>
```

More Examples

| Environment Variable             | Spring Property                  | YAML Path                        |
| -------------------------------- | -------------------------------- | -------------------------------- |
| `SPRING_DATASOURCE_URL`          | `spring.datasource.url`          | `spring.datasource.url`          |
| `SPRING_DATA_REDIS_HOST`         | `spring.data.redis.host`         | `spring.data.redis.host`         |
| `SERVER_PORT`                    | `server.port`                    | `server.port`                    |
| `SPRING_APPLICATION_INSTANCE_ID` | `spring.application.instance-id` | `spring.application.instance-id` |

### Priority Order

Spring Boot reads configuration in this order (highest to lowest priority):

1. Environment variables (highest) ← Docker sets these
2. Command-line arguments
3. `application-{profile}.yaml`
4. `application.yaml` (lowest)

## Useful Commands

### View logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f app
docker-compose logs -f postgres
```

### Restart a service

```bash
docker-compose restart app
```

### Rebuild and restart

```bash
docker-compose up -d --build app
```

### Stop and remove volumes (clean slate)

```bash
docker-compose down -v
```

### Check service health

```bash
docker-compose ps
```

### Execute commands in container

```bash
# Shell access
docker exec -it chat-app sh

# Run Maven command
docker exec -it chat-app mvn clean package
```

## Development

### Hot Reload (Optional)

For development with hot reload, you can mount the source code:

1. Create `docker-compose.override.yml`:

   ```yaml
   version: "3.8"
   services:
     app:
       volumes:
         - ./src:/app/src:ro
   ```

2. Use Spring Boot DevTools (add to `pom.xml`):
   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-devtools</artifactId>
       <optional>true</optional>
   </dependency>
   ```

### Debugging

To enable remote debugging:

1. Update `Dockerfile` to add debug port:

   ```dockerfile
   EXPOSE 9010 5005
   ENTRYPOINT ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-jar", "app.jar"]
   ```

2. Update `docker-compose.yml`:

   ```yaml
   app:
     ports:
       - "9010:9010"
       - "5005:5005"
   ```

3. Connect your IDE to `localhost:5005`

## Production Considerations

For production deployment:

1. **Use environment-specific configs:**

   - Create `application-prod.yaml`
   - Set `SPRING_PROFILES_ACTIVE=prod`

2. **Use secrets management:**

   - Don't commit `.env` file
   - Use Docker secrets or external secret management

3. **Resource limits:**

   ```yaml
   app:
     deploy:
       resources:
         limits:
           cpus: "2"
           memory: 2G
         reservations:
           cpus: "1"
           memory: 1G
   ```

4. **Health checks:**

   - Add Spring Boot Actuator for health endpoints
   - Configure proper health check intervals

5. **Logging:**
   - Use centralized logging (ELK, Loki, etc.)
   - Configure log rotation

## Troubleshooting

### Application won't start

- Check logs: `docker-compose logs app`
- Verify database is ready: `docker-compose ps postgres`
- Check environment variables: `docker-compose config`

### Database connection errors

- Ensure PostgreSQL is healthy: `docker-compose ps postgres`
- Check connection string in environment variables
- Verify network connectivity: `docker network inspect chat-app_chat-network`

### Redis connection errors

- Check Redis password matches in both services
- Verify Redis is healthy: `docker-compose ps redis`
- Test connection: `docker exec -it chat-app-redis redis-cli -a redis123 ping`

### Port conflicts

- Change ports in `docker-compose.yml`
- Check what's using the port: `netstat -an | grep 9010`

## Cleanup

### Remove everything

```bash
docker-compose down -v --remove-orphans
```

### Prune unused resources

```bash
docker system prune -a
```

## Optimize Docker image size

Currently the image's size for the chat-app is 700MB

Why the image is large

- Most of the 700MB comes from the runtime base image (Debian/JRE layers) and the full JDK dependencies embedded in the fat JAR.
- We can reduce size by changing the runtime base or by creating a smaller custom runtime.

Three practical options (tradeoffs included)

1. Use Spring Boot buildpacks (`spring-boot:build-image`)

   - Pros: Produces small, production-ready images (Paketo builders / distroless), minimal manual Dockerfile work.
   - Cons: Requires Docker + Buildpacks (pack) and may change image format; but usually simplest and effective.
   - Command: `mvn spring-boot:build-image -Dspring-boot.build-image.imageName=chat-app:slim`
   - Typical size: 100–250 MB depending on builder.

2. Multi-stage build with a distroless runtime and optional `jlink`

   - Pros: Produces very small image (distroless + custom JRE), deterministic.
   - Cons: More complex; `jlink` step requires careful module selection; may need Java 17+ features and module path support.
   - Implementation: Add a `jlink` stage (or use `jlink` tool in build stage) and copy the custom runtime into a distroless image.

3. Use a slim JRE base image (quick win)
   - Pros: Simple change — replace `eclipse-temurin:25-jre-jammy` with a `-slim` or `-alpine` tag (if available) or with `openjdk:17-jre-slim`.
   - Cons: Must ensure Java runtime version compatibility with the compiled app.
   - Typical size reduction: from ~700MB → ~200–300MB if JRE slim/distroless used.

Recommendations

- If you want the easiest reliable improvement: run `mvn spring-boot:build-image` (Option 1). It usually produces the best size with minimal changes and works well with Spring Boot projects.
- If you prefer a Dockerfile change I can apply now: implement Option 3 (slim base) — low-risk — or Option 2 (distroless+jlink) — higher-reward but I’ll need to adjust build (and possibly pom.xml) and test.

## Layered Jars in Docker

Make sure the project is packaged as a layered jar

- Inspect the jar file: `jar tf ./target/chat-app-0.0.1-SNAPSHOT.jar`
- Result: we can see the layer file: `BOOT-INF/layers.idx`
- List the layers inside the artifact: `java -Djarmode=layertools -jar target/chat-app-0.0.1-SNAPSHOT.jar list`

Why use layered jars

- Docker images are built as a stack of layers. When the application JAR is exported as a layered jar, Spring Boot exposes separate layers for the loader, dependencies, snapshot-dependencies, and application code.
- By extracting and copying these folders into separate Docker COPY steps, Docker can cache unchanged layers (usually the dependencies) between builds. This makes rebuilds much faster and keeps incremental image sizes smaller.

How this repository uses layered jars

- The `Dockerfile` in this repo now uses a multi-stage build that:
  - Builds the application with Maven (`mvn clean package -DskipTests`).
  - Runs `java -Djarmode=layertools -jar target/*.jar extract` in a temporary stage to extract the layered layout.
  - Copies the `spring-boot-loader/`, `dependencies/`, `snapshot-dependencies/` and `application/` folders into the final runtime image as separate Docker layers.

You should see Docker reusing cached layers for `dependencies/` and `spring-boot-loader/` and only rebuilding the `application/` layer, which is much faster.

Notes

- If `java -Djarmode=layertools` reports `BOOT-INF/layers.idx` missing, the jar is not built as a layered jar. Ensure your Spring Boot Maven plugin is recent and not configured to disable layering.
- Layered jars give the most benefit when your dependency set changes infrequently relative to application code.

Ref:

- Copilot
- https://viblo.asia/p/build-optimized-docker-images-for-spring-boot-application-38X4EPqoVN2
- https://www.baeldung.com/docker-layers-spring-boot
