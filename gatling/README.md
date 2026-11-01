# Gatling Skeleton

This is a standalone Gatling project for `chat-app`.

It lives at the repository root as `gatling/` so performance tests stay
separate from the deployable backend service. That keeps load-generator
dependencies, reports, feeders, and future scenarios isolated from
`chat-app-backend`.

## Current scope

Phase 1 only:

- Maven project scaffold
- one HTTP smoke simulation
- one environment/config helper
- one sample feeder file
- one optional `gatling.conf`

This skeleton does **not** implement SockJS/STOMP performance testing yet.

## Folder shape

```text
gatling/
├── pom.xml
├── README.md
└── src/test/
    ├── java/com/hello/chatapp/performance/
    │   ├── config/
    │   ├── scenario/
    │   └── simulation/
    └── resources/
        ├── feeders/
        └── gatling.conf
```

## What the smoke test does

The initial simulation is intentionally small and easy to read:

1. log in through `/api/auth/login`
2. verify the session with `/api/auth/check`
3. load `/api/groups`
4. load one group-history page from `/api/messages/groups/{groupId}`

This is enough to demonstrate:

- project structure;
- HTTP request definitions;
- feeder usage;
- session-cookie reuse;
- basic assertions;
- HTML report generation.

## Prerequisites

- Java 25
- Maven 3.6.3+
- `chat-app-backend` running and reachable
- seed data created:
  - `UserSeeder`
  - `GroupSeeder`

The sample feeder assumes users like `u1` and `u2` with password `5555`, which
matches the current seed documentation.

## Run

From `gatling/`:

```bash
mvn gatling:test
```

You can force a specific simulation class if more are added later:

```bash
mvn gatling:test -Dgatling.simulationClass=com.hello.chatapp.performance.simulation.ChatAppHttpSmokeSimulation
```

## Environment variables

The skeleton supports these optional environment variables:

- `GATLING_BASE_URL` default: `http://localhost:9010`
- `GATLING_SMOKE_USERS` default: `1`
- `GATLING_PAUSE_MILLIS` default: `1000`

## Next steps

- add Maven Wrapper if the team wants wrapper-based commands;
- add baseline and mixed REST scenarios;
- add report-artifact instructions for CI;
- evaluate SockJS/STOMP feasibility separately before adding chat-message
  scenarios.
