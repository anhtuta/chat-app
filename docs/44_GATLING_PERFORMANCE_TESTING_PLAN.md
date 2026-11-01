# Gatling Performance Testing Plan

## Intro

The chat app already has three useful test layers:

- backend and frontend unit/integration tests for isolated correctness;
- Playwright tests in `e2e/` for real-browser user journeys;
- `bot-simulator/` for sustained concurrent STOMP/SockJS message traffic.

What is missing is a repeatable performance test suite with controlled workload
models, response-time measurements, pass/fail assertions, and reports that can
be archived by CI. Gatling is a good fit because the team can write and review
the tests as Java code and run them through Maven.

This document is a plan only. It does not add Gatling or change the application.

## Recommendation

Add a standalone Maven project at `gatling/` in this repository. Do **not** add
Gatling dependencies or simulations to `chat-app-backend`.

Use Gatling Community Edition first for:

1. HTTP session and REST API performance tests.
2. WebSocket connection-capacity tests.
3. A time-boxed proof of concept for the app's actual SockJS + STOMP message
   flow.
4. Repeatable smoke, load, stress, spike, and soak profiles with assertions and
   HTML reports.

Keep `bot-simulator/`. It already uses Spring's real `SockJsClient` and
`WebSocketStompClient`, so it is currently a more faithful protocol client than
Gatling for this application. Gatling and the simulator should share seed data
and scenario assumptions, but they solve different problems.

Do not replace Playwright with Gatling. Playwright answers whether the product
works in a browser; Gatling answers how the backend behaves under controlled
load.

### Important protocol constraint

Gatling supports HTTP and raw WebSocket, but it does not provide first-class
STOMP or SockJS support.

The backend currently registers `/ws` with `.withSockJS()`, and the existing
simulator deliberately uses both `SockJsClient` and `WebSocketStompClient`.
Therefore, a Gatling chat scenario would need to implement:

- the SockJS connection URL and framing;
- STOMP `CONNECT`, `SUBSCRIBE`, `SEND`, heartbeat, and `DISCONNECT` frames;
- matching inbound broadcasts in the presence of unrelated messages;
- session-cookie propagation from HTTP login to the WebSocket handshake.

This is feasible with Gatling's raw WebSocket API, but it is custom test
infrastructure and must be proven before committing to it. Do not add a
test-only native WebSocket endpoint to the backend merely to make Gatling
easier; that would test a different transport from the frontend and the current
bot simulator.

## Functional Requirements

The future Gatling project should:

- log in as seeded users through `/api/auth/login` and retain each user's
  `CHATAPP_SESSION` cookie;
- exercise representative read APIs:
  - list groups;
  - read a group;
  - read group and public message history;
  - check the authenticated session;
- measure WebSocket/SockJS connection establishment if the proof of concept
  succeeds;
- model authenticated STOMP subscribe, send, and receive behavior if the proof
  of concept succeeds;
- distribute users and messages across multiple groups rather than creating one
  accidental database hot spot;
- use unique test-run and message correlation IDs;
- support environment-driven base URLs, credentials, group IDs, load level,
  duration, and pauses;
- provide deterministic workload profiles and reproducible commands;
- validate responses and received messages, not merely generate traffic;
- fail the process when agreed performance assertions are violated;
- generate an HTML report and retain the raw run log as CI artifacts;
- avoid production by default and require an explicit opt-in for any
  non-local target;
- document seed, cleanup, monitoring, and test-environment prerequisites.

## Non-Functional Requirements

- Tests must be version-controlled Java code with no GUI-only configuration.
- Load generation must run outside the backend JVM and preferably outside the
  system-under-test hosts.
- Test data must be isolated by run ID and safe to delete.
- Secrets must come from environment variables or CI secrets, never source
  control.
- Normal pauses and randomized think time should model users; zero-pause loops
  should be reserved for explicitly named stress scenarios.
- The suite must distinguish load-generator saturation from application
  saturation by monitoring both sides.
- Results are comparable only when the environment, seed volume, application
  version, Gatling version, and workload profile are recorded.
- Destructive stress, spike, soak, and resilience tests must not run
  automatically against a shared environment.

## What Kinds of Performance Testing Should We Have?

### 1. Smoke test

A very small workload that proves the simulation, credentials, seed data,
checks, and report generation work.

- Run: pull requests or before a larger test.
- Duration: short.
- Result: functional checks pass and there are no unexpected failed requests.
- Purpose: test the test suite, not establish capacity.

### 2. Baseline test

A fixed, modest workload used to establish normal latency and throughput on a
known environment.

- Run: after meaningful backend changes or on a schedule.
- Compare: current results against an intentionally selected baseline.
- Purpose: detect performance regressions.

Community Edition produces separate static reports, so trend comparison is
manual or requires a separate metrics pipeline. Gatling Enterprise adds
centralized run comparison.

### 3. Load test

The expected normal and peak production workload, held long enough to reach a
steady state.

- HTTP model: use an open model when requests continue to arrive regardless of
  server slowdown.
- Connected-chat model: use a closed model when the requirement is a fixed
  number of simultaneously connected users.
- Purpose: verify service-level objectives under expected demand.

### 4. Stress and breakpoint test

Increase load in controlled steps until an assertion fails or the system
reaches a resource limit.

- Observe the first limiting component: app CPU, JVM, database pool, PostgreSQL,
  Redis, RabbitMQ, network, or load generator.
- Reduce load after the peak to verify recovery.
- Purpose: discover capacity and failure behavior, not produce a passing build.

### 5. Spike test

Apply a sudden burst of logins, WebSocket connections, or messages.

- Useful chat cases: reconnect storm after a short outage, many users opening
  the app together, or a message burst in popular groups.
- Purpose: verify queueing, recovery, and absence of cascading failure.

### 6. Soak test

Hold realistic load for hours.

- Watch heap, threads, connection counts, database sessions, Redis memory,
  RabbitMQ queues, and latency drift.
- Purpose: find leaks, unbounded buffers, stale sessions, and gradual
  degradation that short tests miss.

### 7. Scalability test

Repeat the same workload against one and multiple backend instances.

- Purpose: measure whether throughput scales and whether cross-instance
  RabbitMQ fanout changes latency or error rate.
- Keep database, Redis, and RabbitMQ capacity explicit so the result is not
  misattributed to the backend instance count.

### 8. Resilience test

Run steady load while an operator deliberately restarts or degrades one
dependency or backend instance.

- Gatling generates and measures traffic; it is not the failure-injection tool.
- Purpose: measure errors, reconnect behavior, message loss/duplication, and
  recovery time.
- Run manually in an isolated environment.

## Proposed Test Scenarios

### Phase-one HTTP scenarios

Start here because Gatling supports these flows directly:

1. **Login and bootstrap**
   - log in;
   - check session;
   - load group summaries;
   - load initial public or group history.
2. **History readers**
   - select seeded groups;
   - paginate message history;
   - include realistic pauses between pages.
3. **Mixed REST traffic**
   - mostly reads;
   - a smaller number of safe mutations such as mark-read;
   - group-management and media operations kept in separate scenarios.
4. **Media API control plane**
   - test prepare/complete metadata APIs separately;
   - do not include large object upload bytes until storage cost, cleanup, and
     test-environment capacity are agreed.

### Phase-two connection scenario

After the SockJS proof of concept:

1. log in over HTTP;
2. establish the SockJS WebSocket transport with the same session cookie;
3. complete STOMP `CONNECT`;
4. subscribe to one group and the user's update destination;
5. remain connected for a configured period;
6. disconnect cleanly.

This scenario measures connection setup failures and sustainable concurrent
connections without message-send complexity.

### Phase-three chat scenario

After the connection scenario is reliable:

1. sender and receiver users join pre-seeded groups;
2. clients connect and subscribe;
3. send messages carrying a run ID and correlation ID;
4. match the corresponding inbound broadcast;
5. measure send-to-receive latency and detect missing or duplicate messages;
6. vary group sizes to measure fanout cost.

Use at least three group-size classes:

- small group;
- medium group;
- large/fanout-heavy group.

Exact sizes must come from production expectations rather than arbitrary
numbers.

### Phase-four mixed workload

Run multiple populations in parallel:

- users logging in and loading history;
- mostly idle connected users;
- active senders;
- subscribers receiving group and sidebar updates.

This is the most production-like scenario, but it should be added only after
the smaller scenarios can identify failures independently.

## Measurements and Assertions

### Client-side measurements

- HTTP request rate, failure rate, and response-time percentiles;
- login and bootstrap journey duration;
- WebSocket/SockJS connection success and setup time;
- active connected users;
- STOMP send count;
- correlated send-to-receive latency;
- missing, duplicate, malformed, or unexpected frames;
- disconnects and reconnects.

### Server-side measurements

Use the existing Spring Boot Actuator, Prometheus, and Grafana stack to observe:

- HTTP request rate, latency, and errors;
- JVM CPU, heap, GC pauses, live threads, and process limits;
- WebSocket sessions and subscriptions;
- database connection-pool saturation and slow queries;
- PostgreSQL CPU, locks, I/O, and row growth;
- Redis latency, memory, and session count;
- RabbitMQ publish/delivery rate, queue depth, consumers, and redeliveries;
- each backend instance separately during scaling tests.

### Pass/fail policy

Do not invent final thresholds from one developer laptop. Establish them from
product SLOs and a stable test environment.

Initial assertion categories should include:

- maximum failed-request percentage;
- p95 and p99 latency for each important HTTP request group;
- maximum login/bootstrap journey time;
- maximum failed connection percentage;
- p95 and p99 send-to-receive latency;
- zero unexpected missing or duplicate messages for correctness-focused runs.

TODO: Product/engineering must provide expected concurrent users, message rate,
group-size distribution, target percentiles, allowed error rate, and required
test duration before numeric assertions are committed.

## Gatling vs. the Existing Bot Simulator

| Area | Gatling | `bot-simulator/` |
| --- | --- | --- |
| Primary purpose | Repeatable performance tests with assertions and reports | Generate realistic ongoing STOMP chat traffic |
| Protocol fit | Native HTTP/raw WebSocket; custom SockJS/STOMP needed | Native Spring SockJS/STOMP client, matching this app |
| Workload models | Built-in ramps, rates, concurrency levels, and profiles | Bot count, startup spread, interval, and jitter |
| Validation | HTTP/WebSocket checks and global pass/fail assertions | Primarily connection/send counters |
| Latency reporting | Percentiles and HTML reports | No per-operation latency report today |
| Message correctness | Can be designed around correlation checks | Does not currently verify received broadcasts |
| CI regression gate | Strong fit for small stable scenarios | Weak until deterministic assertions/reporting are added |
| Long-running traffic while observing the app | Possible, but not its unique strength | Strong and simple for the current protocol |
| Maintenance | Scenario code plus custom SockJS/STOMP adapter | Existing Spring client code |

The tools overlap in traffic generation, but the planned Gatling suite is not a
rewrite of the simulator. The simulator is valuable for manual demonstrations,
long-running traffic, reconnection behavior, and protocol fidelity. Gatling is
valuable for controlled experiments, measurements, assertions, and CI reports.

The two tools should share:

- seeded user naming and credentials;
- target group fixtures;
- definitions of small/medium/large groups;
- message correlation format;
- named load profiles and expected rates.

They should not share runtime code initially. Coupling Gatling to the Spring
Boot simulator would make the performance project heavier and blur which
client behavior is being measured.

## Gatling vs. Playwright in `e2e/`

| Area | Gatling | Playwright `e2e/` |
| --- | --- | --- |
| Main question | How fast and how much load can the backend handle? | Can a user complete the feature in a real browser? |
| Client | Protocol-level virtual users | Chromium browser contexts |
| Scale | Hundreds or thousands, subject to generator capacity | Small numbers; browsers are resource-heavy |
| UI rendering | No | Yes |
| Frontend JavaScript and MUI | Not exercised | Exercised |
| HTTP/WebSocket timing | Detailed load-oriented metrics | Functional timing, traces, screenshots |
| Assertions | Performance thresholds and protocol checks | Visible UI and user-flow correctness |
| Best CI use | Small performance smoke; scheduled baseline | Functional regression on pull requests |

Do not use Gatling to prove that sidebar navigation, message rendering, routing,
or browser session behavior works. Do not use Playwright to discover backend
capacity. A useful cross-layer check is:

1. Playwright proves one or two users can complete the chat journey.
2. Gatling proves the backend sustains the required protocol workload.
3. The bot simulator provides faithful sustained STOMP/SockJS traffic during
   manual observation.

## Where Should Gatling Live?

### Option A: standalone `gatling/` Maven project in this repository

- How it works: add a sibling project with its own Maven wrapper, `pom.xml`,
  simulations, resources/feeders, configuration, and README.
- Pros:
  - load generator is isolated from backend dependencies and lifecycle;
  - can target local, test, staging, or a deployed release;
  - reports and test dependencies do not enlarge the production application;
  - consistent with the existing standalone `e2e/` and `bot-simulator/`
    projects;
  - remains easy for Java developers to run and review.
- Cons:
  - one more project and dependency set to maintain;
  - DTOs or endpoint details may drift unless contract helpers stay small.
- Recommendation for this project: **Yes**.

This means a new project folder, not a new Git repository.

### Option B: add Gatling to `chat-app-backend`

- How it works: add the Gatling plugin, dependencies, resources, and simulation
  source set to the backend Maven build.
- Pros:
  - one Maven project;
  - easy access to backend classes.
- Cons:
  - couples black-box performance tests to application internals;
  - risks running load tests through normal `verify`;
  - complicates the existing Surefire/Failsafe test split;
  - mixes load-generator dependencies and reports with the deployable service;
  - encourages in-process shortcuts that do not represent network clients.
- Recommendation for this project: **No**.

### Option C: separate Git repository

- Pros:
  - independent ownership, access, release cadence, and large test artifacts;
  - useful when one suite tests many services or a dedicated performance team
    owns it.
- Cons:
  - version drift from application changes;
  - harder code review and local onboarding;
  - unnecessary operational overhead for the current repository.
- Recommendation for this project: **Not yet**.

## Alternatives to Gatling

### 1. Extend `bot-simulator/`

- Pros:
  - best current SockJS/STOMP fidelity;
  - already authenticates and targets group chats;
  - Java/Spring knowledge transfers directly;
  - full control over reconnect and message semantics.
- Cons:
  - would require building workload models, latency histograms, assertions,
    reports, feeders, and CI integration that Gatling already provides;
  - current successful-send count means the client handed off a frame, not that
    the server processed and broadcast it;
  - easy to create a traffic generator without creating a reliable benchmark.
- Best use: keep it as the protocol-faithful simulator and improve it only when
  a simulator-specific requirement appears.

### 2. Apache JMeter

- Pros:
  - mature and widely known;
  - broad protocol and plugin ecosystem;
  - GUI is useful for exploratory setup;
  - CLI mode can run in CI.
- Cons:
  - test plans are XML and GUI-driven unless the team adds another abstraction;
  - code review and refactoring are less natural than Java source;
  - WebSocket typically relies on plugins;
  - STOMP/SockJS still requires plugin evaluation or manual framing;
  - load-generator resource use can be higher for comparable scenarios.
- Fit here: reasonable if an approved plugin proves SockJS/STOMP support better
  than Gatling, but otherwise it does not remove the central protocol problem.

The preference for coding and version-controlled reviews is a legitimate reason
to prefer Gatling over JMeter, but it should not be presented as JMeter having
native support for this app's complete SockJS/STOMP flow without a proof.

### 3. Grafana k6

- Pros:
  - concise JavaScript/TypeScript tests;
  - strong CLI, thresholds, CI integration, and Prometheus/Grafana ecosystem;
  - efficient load generator;
  - official WebSocket support.
- Cons:
  - no first-class STOMP/SockJS support;
  - STOMP extensions are third-party and require a custom k6 binary;
  - introduces Go-based extension tooling when using `xk6`;
  - less aligned with a Java-focused team.
- Fit here: strongest general alternative if the team prefers
  JavaScript/TypeScript and validates the protocol extension risk.

### 4. Artillery

- Pros:
  - quick YAML scenarios with JavaScript/TypeScript extension points;
  - good HTTP and WebSocket developer experience;
  - easy to begin small.
- Cons:
  - raw WebSocket does not solve SockJS/STOMP automatically;
  - complex chat behavior moves from YAML into custom code;
  - adds another Node.js test project alongside Playwright.
- Fit here: useful for simpler HTTP/raw-WebSocket services, but no clear
  advantage over Gatling for the current Java team.

### 5. Locust

- Pros:
  - Python code, flexible user behavior, and an approachable live UI;
  - distributed execution is available in the open-source tool.
- Cons:
  - HTTP is the main path; WebSocket/STOMP needs custom client integration;
  - custom protocol metrics and connection lifecycle require more engineering;
  - introduces Python tooling not otherwise used by this repository.
- Fit here: good for a Python-oriented team, not the default recommendation.

## High-Level Architecture/Design

```mermaid
flowchart LR
    CI[Developer or CI] --> G[Gatling project]
    CI --> P[Playwright e2e]
    DEV[Developer/manual run] --> B[Bot simulator]

    G -->|HTTP login and APIs| APP[Chat backend instances]
    G -.->|SockJS + STOMP after PoC| APP
    P -->|Real browser journey| FE[React frontend]
    FE --> APP
    B -->|Spring SockJS + STOMP| APP

    APP --> PG[(PostgreSQL)]
    APP --> R[(Redis)]
    APP --> MQ[(RabbitMQ)]

    APP --> ACT[Actuator metrics]
    ACT --> PROM[Prometheus]
    PROM --> DASH[Grafana]
    G --> REPORT[HTML report and assertions]
```

### Proposed project shape

```text
gatling/
├── mvnw
├── mvnw.cmd
├── pom.xml
├── README.md
└── src/test/
    ├── java/com/hello/chatapp/performance/
    │   ├── simulation/
    │   ├── scenario/
    │   ├── protocol/
    │   └── support/
    └── resources/
        ├── feeders/
        └── gatling.conf
```

Keep simulation classes small. Put reusable login, HTTP, workload-profile, and
eventually SockJS/STOMP framing logic in focused helpers. Do not depend on
backend implementation classes.

## Environment and Test Data

Use the existing `UserSeeder` and `GroupSeeder` initially, as both Playwright
and the bot simulator already depend on them. Before trusting benchmark
results, add a documented performance fixture that records:

- number of users;
- number of groups;
- participants per group;
- existing messages per group;
- distribution of group sizes;
- credentials and group IDs available to each scenario.

Large tests should not create all fixture data during the timed section.
Prepare data before the run, verify it in a smoke step, and clean only data
tagged with the current run ID.

Recommended environments:

- local: script development and smoke only;
- stable dedicated performance environment: baselines, load, stress, and soak;
- production: no load test unless owners explicitly approve scope, rate,
  timing, safeguards, and cleanup.

## CI and Execution Strategy

1. **Pull request**
   - compile simulations;
   - optionally run a tiny HTTP smoke test in an ephemeral environment;
   - archive the report on failure and success.
2. **Scheduled**
   - run the baseline profile against a stable environment;
   - store application commit, environment description, and report artifact.
3. **Manual**
   - run load, stress, spike, soak, scalability, and resilience profiles;
   - require an operator to watch Gatling plus Grafana.

Do not bind substantial load tests to the default backend Maven lifecycle.

## Implementation Details

### Phase 0 - Agree on the performance contract

- Define expected concurrency, arrival rates, message rates, group-size
  distribution, SLOs, environment, and test ownership.
- Record the hardware and dependency topology of the baseline environment.

For a first performance-testing project, these terms usually mean:

- **Expected concurrency**
  - Meaning: how many users are connected or active at the same time.
  - Why it matters: this drives WebSocket/session pressure, memory usage, and
    fanout cost.
  - Starter default:
    - smoke: `5-10` connected users;
    - baseline: `25-50` connected users;
    - first load target: `100` connected users;
    - first stress target: `250` connected users.
- **Arrival rate**
  - Meaning: how many new user journeys start per second, such as logins or
    page-entry flows.
  - Why it matters: this is the right model for most HTTP traffic because new
    users keep arriving even when the app slows down.
  - Starter default:
    - smoke: `1 user every 2-5 seconds`;
    - baseline: `2-5 users/second`;
    - first stress ramp: `10-20 users/second`.
- **Message rate**
  - Meaning: how many chat messages the system receives per second.
  - Why it matters: this is the core write pressure for the chat product and
    strongly affects database, broker, and fanout behavior.
  - Starter default:
    - baseline: `5 messages/second` total;
    - first load target: `20 messages/second` total;
    - first stress target: `50 messages/second` total.
- **Group-size distribution**
  - Meaning: how many members are typically in a group, and what the mix of
    small, medium, and large groups looks like.
  - Why it matters: fanout cost for one message depends heavily on how many
    subscribers receive it.
  - Starter default:
    - small: `5-10` members;
    - medium: `25-50` members;
    - large: `100-200` members.
- **SLOs**
  - Meaning: service-level objectives, or the latency/error targets that the
    system should meet under a defined workload.
  - Why it matters: without these, the team can generate load but cannot say
    whether the result is acceptable.
  - Starter default:
    - read APIs: p95 under `300-500 ms`, p99 under `800-1000 ms`;
    - login/bootstrap journey: p95 under `2 seconds`;
    - WebSocket connect: p95 under `2-3 seconds`;
    - message send-to-receive: p95 under `1 second`;
    - failed requests: under `1%` in baseline/load tests.
- **Environment**
  - Meaning: where the tests run and what infrastructure shape they hit.
  - Why it matters: laptop results are useful for learning, but not for
    trustworthy capacity numbers.
  - Starter default:
    - local machine: script development and smoke only;
    - one stable shared environment: baseline and regression tracking;
    - manually approved environment: stress and soak.
- **Test ownership**
  - Meaning: who updates scripts, runs tests, watches dashboards, and signs off
    on results.
  - Why it matters: performance suites die quickly when everyone assumes
    someone else owns them.
  - Starter default:
    - backend engineer owns the scripts;
    - one teammate owns dashboards/report storage;
    - product/backend lead approves thresholds and test scope.
- **Hardware and dependency topology**
  - Meaning: the CPU, memory, instance count, and dependency layout for the app,
    PostgreSQL, Redis, RabbitMQ, and load generator.
  - Why it matters: a result is hard to compare if the environment shape keeps
    changing.
  - Starter default:
    - write down app instance count;
    - CPU and RAM for each service;
    - database/Redis/RabbitMQ host placement;
    - whether Gatling runs on the same machine or a separate one.

These starter defaults are not promises about production capacity. They are
safe first assumptions so the team can learn the tooling, validate the
scenarios, and establish a baseline before choosing harder targets.

### Phase 1 - Standalone Gatling scaffold and HTTP smoke **Done**

- What changed:
  - added a standalone `gatling/` Maven project at the repository root;
  - added a small HTTP smoke simulation for login, session check, group list,
    and one group-history request;
  - added a central environment/config helper;
  - added a sample feeder file with seeded users;
  - added a starter `README.md` and `gatling.conf`.
- Why it changed:
  - this gives the team a concrete project shape to inspect before adding more
    workload types;
  - it keeps performance-test code separate from `chat-app-backend`;
  - it provides a minimal runnable baseline for later Phase 2 scenarios.
- Rollout, migration, or backward-compatibility notes:
  - the current scaffold is HTTP-only;
  - SockJS/STOMP simulations are still deferred to the feasibility phase;
  - Maven Wrapper can be added later if the team wants wrapper-based commands.

### Phase 2 - HTTP baseline and load scenarios

- Add bootstrap, history, and mixed REST scenarios.
- Correlate Gatling results with Prometheus/Grafana.

### Phase 3 - SockJS/STOMP feasibility proof

- Implement the minimum connection and STOMP handshake.
- Verify cookies, heartbeats, subscription, one correlated send/receive, and
  clean disconnect.
- Compare behavior with browser traffic and `bot-simulator/`.
- Stop and reassess Gatling for chat traffic if protocol fidelity or
  maintainability is poor.

### Phase 4 - Connection and messaging profiles

- Add connection capacity, group fanout, mixed traffic, stress, and spike
  simulations.
- Add correctness checks for missing and duplicate correlated messages.

### Phase 5 - Baseline automation

- Add scheduled CI execution on a stable environment, artifact retention, run
  metadata, and an agreed regression-review process.

### Phase 6 - Soak, resilience, and scaling

- Add manual runbooks for long tests, controlled failures, multi-instance
  comparisons, cleanup, and result interpretation.

## Open Questions Before Implementation

These questions do not block creation of the project scaffold, but they do
block meaningful load levels and pass/fail thresholds:

1. What are the expected average and peak concurrently connected users?
2. What are the expected average and peak messages per second?
3. What do small, medium, and large groups look like in production?
4. Which latency percentiles and error rates has the business promised?
5. Is there a dedicated staging/performance environment, and what is its
   topology relative to production?
6. Should CI run only smoke tests, or is a stable scheduled baseline
   environment available?
7. How long must the soak test run?
8. Is Gatling Community Edition sufficient, or is there budget/need for
   Enterprise distributed load generation and centralized trend comparison?
9. Are media upload/download workloads in scope, and what storage cost and
   cleanup limits apply?
10. Is SockJS still a product requirement, or is a future migration to native
    WebSocket already planned for real clients?

TODO: Answer these questions with the product owner, backend owner, and
operations owner before assigning numeric targets.

### Suggested Default Starter Answers

If the team has no production numbers yet, use the following defaults as a
temporary starting point for the first Gatling project:

1. **Expected average and peak concurrently connected users**
   - Default starter answer:
     - average: `25` connected users;
     - peak: `100` connected users.
   - Default options:
     - `Small team / learning phase`: average `10`, peak `25`
     - `Recommended starter`: average `25`, peak `100`
     - `Aggressive early stretch`: average `100`, peak `250`
   - Recommendation: begin with `Recommended starter`.

2. **Expected average and peak messages per second**
   - Default starter answer:
     - average: `5 messages/second`;
     - peak: `20 messages/second`.
   - Default options:
     - `Light chat traffic`: average `2`, peak `10`
     - `Recommended starter`: average `5`, peak `20`
     - `Aggressive early stretch`: average `20`, peak `50`
   - Recommendation: begin with `Recommended starter`.

3. **Definition of small, medium, and large groups**
   - Default starter answer:
     - small: `5-10` members;
     - medium: `25-50` members;
     - large: `100-200` members.
   - Default options:
     - `Conservative`: small `5`, medium `20`, large `50`
     - `Recommended starter`: small `10`, medium `50`, large `200`
     - `Fanout-heavy`: small `20`, medium `100`, large `500`
   - Recommendation: begin with `Recommended starter`.

4. **Latency percentiles and error-rate targets**
   - Default starter answer:
     - read APIs: p95 `< 500 ms`, p99 `< 1 s`;
     - login/bootstrap: p95 `< 2 s`;
     - WebSocket connect: p95 `< 3 s`;
     - message send-to-receive: p95 `< 1 s`;
     - failed requests: `< 1%`.
   - Default options:
     - `Loose starter`: p95 `< 1 s` for reads, `< 2%` failures
     - `Recommended starter`: values above
     - `Strict stretch`: p95 `< 300 ms` for reads, `< 0.5%` failures
   - Recommendation: begin with `Recommended starter`.

5. **Dedicated staging/performance environment**
   - Default starter answer:
     - yes, if available, use one stable shared environment for baseline runs;
     - if not available, use local only for smoke and learning.
   - Default options:
     - `Local only`: fastest to begin, lowest confidence
     - `Recommended starter`: one shared non-production environment
     - `Advanced`: production-like dedicated performance environment
   - Recommendation: begin with `Recommended starter` when possible.

6. **What should CI run?**
   - Default starter answer:
     - pull requests run smoke only;
     - scheduled job runs the baseline scenario.
   - Default options:
     - `PR smoke only`
     - `Recommended starter`: PR smoke + nightly baseline
     - `Advanced`: PR smoke + nightly baseline + weekly stress
   - Recommendation: begin with `Recommended starter`.

7. **How long should the soak test run?**
   - Default starter answer: start with `1 hour`.
   - Default options:
     - `Quick soak`: `1 hour`
     - `Recommended starter`: `4 hours`
     - `Deep soak`: `8-12 hours`
   - Recommendation: start with `1 hour` while building confidence, then move
     to `4 hours`.

8. **Community Edition or Enterprise?**
   - Default starter answer: use **Gatling Community Edition** first.
   - Default options:
     - `Community only`: local/single-generator usage, static reports
     - `Recommended starter`: Community now, re-evaluate later
     - `Enterprise`: when distributed load and centralized history are needed
   - Recommendation: begin with `Community now, re-evaluate later`.

9. **Are media upload/download workloads in scope?**
   - Default starter answer:
     - phase 1: **no** for large media bytes;
     - phase 1 may include only media metadata/control-plane APIs.
   - Default options:
     - `Out of scope initially`
     - `Recommended starter`: metadata APIs only
     - `Full scope later`: include real upload/download traffic
   - Recommendation: begin with `Recommended starter`.

10. **Is SockJS still required, or will native WebSocket replace it soon?**
    - Default starter answer: assume **SockJS is still required** until the
      frontend is officially migrated.
    - Default options:
      - `Current reality`: SockJS remains in scope
      - `Recommended starter`: test the current SockJS/STOMP path first
      - `Future migration path`: native WebSocket after a product decision
    - Recommendation: begin with `Recommended starter`.

TODO: Replace these starter answers with real product and operations data once
the team has traffic expectations, a stable test environment, and early run
history.

## Decision Criteria After the SockJS/STOMP Proof of Concept

Continue with Gatling for chat messaging only if:

- its client follows the same SockJS transport path as the frontend;
- STOMP heartbeat and framing behavior are correct;
- inbound messages can be correlated under fanout without excessive buffering;
- client-side resource use is measured and leaves sufficient generator
  headroom;
- the adapter remains small, tested, and understandable to the team.

If those criteria fail, use Gatling for HTTP/API performance and retain or
enhance `bot-simulator/` for SockJS/STOMP measurements. A mixed-tool strategy is
better than reporting precise-looking results from an unrealistic client.

## Future Higher-Scale Path

- Move heavy load generation to dedicated hosts.
- Use Gatling Enterprise if distributed generation, geographic load,
  centralized history, and collaborative reports justify the cost.
- Export run metadata and application metrics to a durable performance
  dashboard.
- Add capacity models per backend instance and per dependency tier.
- Split media and chat-message tests when their resource profiles interfere.
- Re-evaluate k6 or a specialized client if protocol requirements move away
  from the JVM or if SockJS/STOMP support becomes stronger elsewhere.

## References

- [Gatling Java/JVM installation guide](https://docs.gatling.io/tutorials/test-as-code/java-jvm/installation-guide/)
- [Gatling WebSocket reference](https://docs.gatling.io/reference/script/websocket/)
- [Gatling assertions](https://docs.gatling.io/concepts/assertions/)
- [Gatling injection profiles](https://docs.gatling.io/concepts/injection/)
- [Gatling workload models](https://docs.gatling.io/testing-concepts/workload-models/)
- [Gatling Maven plugin](https://docs.gatling.io/integrations/build-tools/maven-plugin/)
- [Gatling Community static reports](https://docs.gatling.io/reference/stats/reports/oss/)
- [Gatling licenses](https://docs.gatling.io/project/licenses/project-licenses/)
- [Grafana k6 WebSocket documentation](https://grafana.com/docs/k6/latest/using-k6/protocols/websockets/)
- Existing project docs:
  [Bot Simulator](08_BOT_SIMULATOR.md),
  [Playwright E2E](09_E2E_PLAYWRIGHT.md), and
  [Monitoring](04_MONITORING.md)
