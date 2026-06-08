# Prometheus + Grafana Monitoring Guide (Beginner-Friendly)

This guide explains what Prometheus and Grafana do, how they work together in this project, and how to use them step by step for your Spring Boot app.

## 1) What are Prometheus and Grafana?

- Prometheus: collects metrics from apps on a schedule and stores them as time-series data.
- Grafana: reads metrics from Prometheus and shows charts, dashboards, and alerts.

Think of it like this:

- Spring Boot = metric producer
- Prometheus = metric database + query engine
- Grafana = UI for charts and dashboards

Flow: Spring Boot Actuator exposes metrics -> Prometheus scrapes them every interval -> Grafana reads Prometheus and renders charts

## 2) How this repository is wired

Your app exposes metrics through Spring Boot Actuator at:

- http://localhost:9010/actuator/prometheus

Prometheus scrapes the app target from:

- docker/prometheus.yml
- target: host.docker.internal:9010 (so Prometheus in Docker can scrape app running in your IDE)

Grafana connects to Prometheus via provisioning:

- docker/grafana/provisioning/datasources/prometheus.yml
- datasource URL: http://prometheus:9090 (Docker service name)

Dashboard provisioning is configured at:

- docker/grafana/provisioning/dashboards/dashboards.yml
- dashboard JSON file: docker/grafana/provisioning/dashboards/chat-app-dashboard.json

## 3) Start monitoring (recommended for IDE debugging)

This mode is best when you run Spring Boot from IntelliJ/VS Code for debugging.

### Step A: Start only infra + monitoring in Docker

```bash
cd chat-app-backend
docker compose up -d postgres redis rabbitmq prometheus grafana
```

### Step B: Start Spring Boot app from IDE

Run your backend normally in debug mode on port 9010.

### Step C: Open the UIs

- App health: http://localhost:9010/actuator/health
- App metrics (Prometheus format): http://localhost:9010/actuator/prometheus
- Prometheus: http://localhost:9090
- Prometheus targets page: http://localhost:9090/targets
- Grafana: http://localhost:3010

Grafana login:

- Username: admin
- Password: admin (or value from GRAFANA_PASSWORD)

## 4) First checks (important for beginners)

If you are new, always verify in this exact order:

1. App endpoint works:
   - http://localhost:9010/actuator/prometheus returns text metrics
2. Prometheus can scrape app:
   - http://localhost:9090/targets shows host.docker.internal:9010 as UP
3. Grafana datasource works:
   - In Grafana, Data sources -> Prometheus should be healthy
4. Dashboard has data:
   - Open the chat app dashboard and set time range to Last 15 minutes

## 5) Basic Prometheus usage (PromQL)

Open http://localhost:9090/graph and try these queries:

```promql
# Is target up? 1 means UP, 0 means DOWN
up

# JVM heap used bytes
jvm_memory_used_bytes{area="heap"}

# CPU usage for current process
process_cpu_usage

# Total HTTP requests count
http_server_requests_seconds_count

# Request rate per second (last 1 minute window)
rate(http_server_requests_seconds_count[1m])
```

How to read them:

- Instant query: value right now
- Graph query: value over time
- rate(...): how fast a counter grows, useful for throughput

## 6) Basic Grafana usage

### Explore mode (quick learning)

1. Open Grafana -> Explore
2. Select Prometheus datasource
3. Paste a PromQL query (for example: up)
4. Click Run query
5. Change time range in top-right

### Dashboard mode (daily monitoring)

1. Open Dashboards
2. Select chat app dashboard
3. Use time range + refresh interval
4. Use panel menu -> Inspect when values look wrong

## 7) Useful starter metrics for Spring Boot

```promql
# JVM heap memory
jvm_memory_used_bytes{area="heap"}

# JVM GC pause time total
jvm_gc_pause_seconds_sum

# Live threads
jvm_threads_live_threads

# HTTP request rate
rate(http_server_requests_seconds_count[1m])

# 95th percentile latency (if histogram buckets are available)
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))
```

Note:

- If a query returns nothing, the metric may not exist in your current app build/version yet.
- In that case, search metric names first in Prometheus UI.

## 8) Troubleshooting quick fixes

1. No data in Grafana:
   - Check Prometheus targets page first.
   - If target is DOWN, verify the app is running on 9010.
2. Prometheus target DOWN when app runs in IDE:
   - Confirm target uses host.docker.internal:9010 in docker/prometheus.yml.
   - Restart Prometheus container after changing config.
3. Grafana opens but no dashboards:
   - Verify dashboard provisioning files under docker/grafana/provisioning/dashboards.
4. Wrong Grafana port:
   - This repo maps Grafana to http://localhost:3010 (not 3000).

## 9) Common commands

```bash
# Start monitoring stack
cd chat-app-backend
docker compose up -d postgres redis rabbitmq prometheus grafana

# Restart Prometheus after config changes
docker compose restart prometheus

# See Prometheus logs
docker logs chat-app-prometheus

# See Grafana logs
docker logs chat-app-grafana

# Stop monitoring stack
docker compose stop prometheus grafana postgres redis rabbitmq
```

## 10) Next step after basics

After you are comfortable, add alerting rules for:

- service down (up == 0)
- high latency (p95)
- high error rate
- JVM memory pressure

That is where monitoring becomes proactive, not just visual.
