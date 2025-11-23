# Docker Quick Start

All commands should be run in the root folder

## Prerequisites

- Docker Engine 20.10+
- Docker Compose 2.0+

## Quick Start

```bash
# Make script executable (Linux/Mac)
chmod +x docker-start.sh

# Start all services
./docker-start.sh

# Or manually:
docker-compose up -d
```

## Services

- **Application**: http://localhost:9010
- **PostgreSQL**: localhost:5432
- **Redis**: localhost:6379
- **RabbitMQ Management**: http://localhost:15672 (guest/guest)

## Multi-Instance Setup

```bash
docker-compose --profile multi-instance up -d
```

This starts two app instances:

- Instance 1: http://localhost:9010
- Instance 2: http://localhost:9011

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

## Useful Commands

```bash
# View logs
docker-compose logs -f app

# Stop services
docker-compose down

# Stop and remove volumes
docker-compose down -v

# Rebuild app
docker-compose up -d --build app
```

For more details, see [DOCKER.md](./DOCKER.md).
