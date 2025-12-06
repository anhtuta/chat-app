# Docker Quick Start

All commands should be run in the root folder

## Prerequisites

- Docker Engine 20.10+
- Docker Compose 2.0+

## Quick Start

Use bash scripts

```bash
# Make script executable (Linux/Mac)
chmod +x docker-start.sh

# Start all services
./docker-start.sh

# Stop all services
./docker-stop.sh
```

Or manually

```bash
# Single instance
docker-compose up -d

# Multi-instance
docker-compose --profile multi-instance up -d --build

# Restart services after modifying code
docker compose up -d --build instance-1
docker compose up -d --build instance-2
docker compose up -d --build instance-3

# Stop services
docker-compose down

# Stop and remove volumes
docker-compose down -v
```

## Services

- **Application**: http://localhost:9010
- **PostgreSQL**: localhost:5432
- **Redis**: localhost:6379
- **RabbitMQ Management**: http://localhost:15672 (guest/guest)

## Environment Variables

Create a `.env` file (or use defaults):

```env
POSTGRES_PASSWORD=5555
REDIS_PASSWORD=redis123
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=guest
INSTANCE_ID=instance-1
LOG_LEVEL=INFO
```

For more details, see [DOCKER.md](./DOCKER.md).
