# bot-simulator

Standalone Spring Boot app that load-tests your chat backend with WebSocket STOMP bots using Java virtual threads.

## What it does

- Logs in existing users through `/api/auth/login`.
- Opens authenticated WebSocket STOMP sessions at `/ws` using the `JSESSIONID` cookie.
- Sends group messages continuously to `/app/group.send`.
- Runs one bot per virtual thread, so scaling to 1000 bots is practical.
- Prints runtime metrics every few seconds.

## Requirements

- Java 25
- Maven
- Main chat app running and reachable (default http://localhost:9010)

## Run

From bot-simulator folder:

```bash
./mvnw spring-boot:run
```

## Default behavior

- 1000 bots using usernames u1 to u1000
- Password for all bots: `5555`
- Target group IDs: `[1,2,3,4,5,6,7,8,9,10]`
- Send interval: `500ms + jitter`

Important: each bot must already be a participant in target groups, otherwise group sends will be rejected by your backend.

## Configuration

All simulator settings are environment-variable driven.

- `SIM_BASE_URL`: chat backend base URL
- `SIM_BOT_COUNT`: number of bots
- `SIM_TARGET_GROUP_IDS`: comma-separated group IDs (example: 1,2,3)
- `SIM_SEND_INTERVAL_MS`: base interval between messages
- `SIM_SEND_JITTER_MS`: random jitter added to interval
- `SIM_STARTUP_SPREAD_MS`: spread bot startup to avoid all connecting at once

### Example: existing groups mode

```bash
SIM_BOT_COUNT=1000 \
SIM_BOT_USERNAME_PREFIX=u \
SIM_BOT_PASSWORD=5555 \
SIM_TARGET_GROUP_IDS=1,2,3,4,5,6,7,8,9,10 \
SIM_SEND_INTERVAL_MS=300 \
./mvnw spring-boot:run
```

## Metrics logs

You will see periodic logs like:

- connectedBots: currently connected bot sessions
- sentMessages: total messages sent
- failedMessages: send failures
- connectFailures: failed connect/login cycles

## Tips

- Start with 100 bots first, then ramp to 1000.
- Use multiple group IDs to spread write contention.
- Watch DB, Redis, RabbitMQ, and JVM metrics while running this simulator.
