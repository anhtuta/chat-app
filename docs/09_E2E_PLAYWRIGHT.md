## Current Problem

The chat app has Jest unit tests on the frontend and JUnit tests on the backend, but no end-to-end coverage for the full login → WebSocket → chat flow. Regressions in routing (`switchToChat`), sidebar navigation, message send, or session handling would only show up manually.

## Possible Solutions

### 1. Playwright in `e2e/` (pnpm)

- How it works: a self-contained `e2e/` package runs browser tests against the React dev server (`localhost:3000`) or embedded static (`localhost:9010`), using seed users (`u1` / `5555`) like bot-simulator.
- Pros: keeps the repo root as a workspace only; matches real dev UX; good debugging (trace, UI mode).
- Cons: requires infra + backend running; WebSocket timing can be flaky without waits.
- Recommendation for our problem: Yes.

### 2. Cypress in frontend package

- How it works: install Cypress inside `chat-app-frontend` and colocate specs with components.
- Pros: familiar DX for React teams.
- Cons: couples E2E to the frontend npm project; harder to orchestrate backend from a nested folder.
- Recommendation for our problem: No.

### 3. Extend bot-simulator for UI checks

- How it works: add HTTP/HTML assertions to the Java load-test client.
- Pros: reuses existing seed login flow.
- Cons: wrong tool for UI; no browser rendering, brittle for React/MUI.
- Recommendation for our problem: No.

## Chosen Solution

Added a Playwright project under `e2e/` (not at repo root):

- **Package manager:** pnpm (`e2e/package.json`, Node ≥ 24 via `e2e/.nvmrc`)
- **Config:** `e2e/playwright.config.ts`
- **Seed credentials:** `E2E_USERNAME` / `E2E_PASSWORD` (default `u1` / `5555`)
- **Auth reuse:** `tests/auth.setup.ts` logs in once and saves `storageState` for chat tests
- **Coverage:**
  - Login page render, invalid credentials, seed login
  - Sidebar (public chat, seeded groups, New Group button)
  - Chat area composer
  - Send message (public + group)
  - `switchToChat` via sidebar click and URL param (`/group/2`)

### Prerequisites

Same as bot-simulator — seed data must exist:

1. `make start.deps && make db.migrate` (in `chat-app-backend`)
2. Run `UserSeeder` then `GroupSeeder` once
3. Start the app using one of:
   - **Dev proxy:** `make run.be` + `make run.fe` → set `E2E_BASE_URL=http://localhost:3000` (Playwright can auto-start FE)
   - **Embedded static:** `make run.local` → set `E2E_BASE_URL=http://localhost:9010` and `E2E_SKIP_WEBSERVER=1`

If port 3000 is occupied by another process, either free it or use the `9010` workflow above.

### Commands

```bash
cd e2e
pnpm install
pnpm exec playwright install chromium
pnpm test

# Or with env overrides:
E2E_BASE_URL=http://localhost:9010 E2E_SKIP_WEBSERVER=1 pnpm test
pnpm test:ui      # interactive debugger
pnpm test:headed  # visible browser
```

### Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `E2E_BASE_URL` | `http://localhost:3000` | Frontend base URL |
| `E2E_USERNAME` | `u1` | Seed username |
| `E2E_PASSWORD` | `5555` | Seed password |
| `E2E_GROUP_NAME` | `Group 1` | Sidebar group to click in tests |
| `E2E_SKIP_WEBSERVER` | unset | Set to `1` if FE is already running |

## Future Higher-Scale Path

- Add `data-testid` hooks on login, sidebar items, composer, and connection chip for more stable selectors.
- CI job: docker-compose deps → migrate → seed → start BE → `cd e2e && pnpm test`.
- Multi-user scenarios using separate Playwright browser contexts (Redis session isolation).
- Visual regression or theme-switch tests once core flows are stable.
