# Data Directory

This directory contains persistent data for Docker containers.

## Structure

```
data/
├── postgres/     # PostgreSQL database files (mounted from container)
├── redis/        # Redis data files (AOF persistence)
├── rabbitmq/     # RabbitMQ data files (queues, exchanges, etc.)
└── README.md     # This file
```

## Important Notes

⚠️ **Do NOT delete this directory** while containers are running, as it contains your database data.

⚠️ **Backup before cleanup**: If you need to reset any service data, stop containers first:

```bash
docker-compose down
# Then you can safely delete:
# - data/postgres/    (to reset database)
# - data/redis/       (to reset Redis data)
# - data/rabbitmq/    (to reset RabbitMQ data)
# Or delete data/ entirely to reset everything
```
