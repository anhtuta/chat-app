## Current Problem

Rebuilding the backend Docker image (`docker compose up -d --build instance-2`) took ~360 seconds. Most of that time was spent in `mvn dependency:go-offline`, which re-downloaded a large dependency graph on every build — including many Spring Boot BOM artifacts this app never uses (e.g. Google Cloud libraries).

Docker layer caching alone was not enough: when the `pom.xml` layer was invalidated, Maven's local repository (`~/.m2`) was discarded with the build container, so dependencies had to be fetched again from the network.

## Possible Solutions

### 1. Keep `dependency:go-offline` with Docker layer cache only

- How it works: Copy `pom.xml`, run `go-offline`, then copy `src` and build.
- Pros: Simple; no Dockerfile syntax changes.
- Cons: `go-offline` is slow and over-fetches; cache is lost whenever the dependency layer rebuilds.
- Recommendation for our problem: No

### 2. BuildKit cache mount for `~/.m2` + `dependency:resolve`

- How it works: Use `# syntax=docker/dockerfile:1` and `RUN --mount=type=cache,target=/root/.m2` so Maven artifacts persist across builds. Replace `go-offline` with `dependency:resolve dependency:resolve-plugins`, which only resolves artifacts required by this `pom.xml`.
- Pros: Large speedup on repeated builds; smaller download set; works even when `pom.xml` changes incrementally.
- Cons: Requires BuildKit (enabled by default in recent Docker Desktop / Compose).
- Recommendation for our problem: Yes

## Chosen Solution + Implementation

**Phase 1 (implemented):** Optimize `chat-app-backend/Dockerfile`

1. Enable BuildKit Dockerfile syntax.
2. Mount a persistent cache at `/root/.m2` for both Maven `RUN` steps.
3. Replace `dependency:go-offline` with `dependency:resolve dependency:resolve-plugins`.
4. Add `-ntp` to reduce Maven log overhead during image builds.

**Expected build times**

| Scenario                     | Before | After (approx.)       |
| ---------------------------- | ------ | --------------------- |
| First build (cold cache)     | ~360s  | ~60–120s              |
| Rebuild, only `src/` changed | ~360s  | ~15–40s               |
| Rebuild, `pom.xml` changed   | ~360s  | ~30–90s (incremental) |

## Future Higher-Scale Path

- Use Spring Boot layered JAR extraction in the runtime image for faster container restarts when only app code changes.
- Add a CI registry cache for `/root/.m2` on remote builders.
- Pin base image digests in CI for reproducible builds.
