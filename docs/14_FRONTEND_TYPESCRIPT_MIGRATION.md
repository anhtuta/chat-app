## Current Problem

`chat-app-frontend` is still written in plain JavaScript even though the app is already growing in scope and complexity.

The current frontend includes:

- Page-level orchestration in `src/pages/ChatPage.js`
- Shared runtime state in `src/context/WebSocketProvider.js`
- API and WebSocket boundary code in `src/services/`
- A growing set of presentational and feature components in `src/components/`

We want TypeScript primarily to improve maintainability and refactoring safety, not to block feature delivery with a full rewrite.

## Possible Solutions

### 1. Big-bang conversion of the entire frontend

- How it works: rename most or all frontend files to `.ts` / `.tsx`, add TypeScript config, and fix all compile issues in one migration.
- Pros: fast arrival at a fully typed codebase; one migration story.
- Cons: high risk, large diff, easy to stall feature work, harder to review, harder to debug if build/test issues appear.
- Recommendation for our problem: No

### 2. Incremental migration with `allowJs`

- How it works: add TypeScript tooling and config first, keep JavaScript files working, then convert modules in a planned order over multiple small PRs.
- Pros: low-risk rollout, easier review, preserves velocity, lets us prioritize the highest-value files first.
- Cons: temporary mixed JS/TS codebase; some duplication while shared types settle.
- Recommendation for our problem: Yes

### 3. Combine TypeScript migration with a bundler migration

- How it works: move from CRA / `react-scripts` to another toolchain such as Vite while also converting the codebase to TypeScript.
- Pros: could modernize tooling and TypeScript support in one project.
- Cons: mixes two large changes together; harder to isolate regressions; larger review and rollback surface.
- Recommendation for our problem: No
- When I'd use it (only if NOT recommended): only after TypeScript is established, or if we intentionally schedule a dedicated frontend platform migration.

## High level Architecture/Design

```text
React Pages / Components
        |
        v
Typed hooks / context providers
        |
        v
Typed service layer (`api.ts`, `websocket.ts`)
        |
        v
Backend REST + WebSocket contracts

Shared `src/types/*` sits across the frontend layers and defines
stable shapes for messages, groups, auth, theme, and websocket events.
```

## Recommendation

Recommendation path:

1. Phase 1: Add TypeScript support to the existing CRA frontend without changing runtime behavior.
2. Phase 2: Introduce shared domain types for auth, messages, groups, themes, and websocket contracts.
3. Phase 3: Convert app-shell and boundary files first (`index`, `App`, services, basic utilities).
4. Phase 4: Convert service boundaries (`api`, `websocket`, shared media helpers).
5. Phase 5: Convert shared runtime state (`WebSocketProvider`).
6. Phase 6: Convert page-level orchestration (`ChatPage`, auth pages).
7. Phase 7: Convert presentational components and tests.
8. Phase 8: Tighten TypeScript rules gradually and remove `allowJs` when the remaining JavaScript surface is small.
9. Phase 9: Adopt and enforce `pnpm` in `chat-app-frontend` (standalone package, no repo-root workspace).
10. Phase 10: Replace CRA dev/build with Vite and remove `react-scripts` from those paths.
11. Phase 11: Replace CRA test runner with Vitest and remove `react-scripts` entirely.
12. Phase 12: Remove remaining CRA files, align Spring static embed, and update repo docs/CI.

### Recorded Decision

- TypeScript comes first.
- `pnpm` and Vite come **after** the TypeScript migration phases above, as separate tooling migrations.
- Do **not** combine TypeScript file conversion with bundler migration in the same PR unless the change is purely tooling/config.
- We will **not** introduce a repo-root pnpm workspace. `chat-app-frontend/` and `e2e/` stay separate packages with their own lockfiles and install commands.
- We will **not** keep `react-scripts` after the Vite/Vitest migration. Vite replaces CRA dev/build; Vitest replaces CRA tests. Any overlap is only a short bridge between Phase 9 and Phase 11 PRs.
- After Phase 9, frontend docs and Makefiles must use **`pnpm` only** — no `npm` or `yarn` commands in project scripts, Makefiles, or contributor docs.
- We will keep the app as a React SPA for now and will not move to Next.js in this migration.
- We should only revisit Next.js if future requirements demand SSR, SSG, or a React-owned server layer that Spring Boot does not already cover.
- `e2e/` already uses `pnpm`; aligning `chat-app-frontend` on `pnpm` reduces package-manager drift across the repo.

## Chosen Solution + Implementation

Chosen path: **incremental migration with `allowJs`**, keeping runtime behavior stable while improving type coverage in reviewable slices.

Planned implementation phases:

### Phase 1: Tooling bootstrap

- Status: Implemented
- Added `typescript` and React/Jest/Node/SockJS type packages to `chat-app-frontend`.
- Pinned `typescript` to `4.9.5` for compatibility with `react-scripts@5`.
- Added a `tsconfig.json` suitable for CRA.
- Started with pragmatic settings such as:
  - `allowJs: true`
  - `checkJs: false`
  - `noEmit: true`
  - `strict: false` initially
- Kept the existing JavaScript source files unchanged in this phase so TypeScript support can land without mixing in conversion work.
- Verification:
  - `npm run build` passes
  - `CI=true npm test -- --watchAll=false --runInBand` passes
  - the old CRA starter test was replaced with a small app smoke test because it no longer matched the real UI

Notes:

- Phase 1 is intentionally only a tooling/bootstrap step.
- File conversion to `.ts` / `.tsx` starts in later phases.
- Remaining frontend ESLint warnings seen during build are pre-existing and are not introduced by this phase.

### Phase 2: Shared types foundation

- Status: Implemented
- Created `src/types/` for stable frontend contracts.
- Added:
  - `src/types/auth.ts`
  - `src/types/chat.ts`
  - `src/types/groups.ts`
  - `src/types/theme.ts`
  - `src/types/websocket.ts`
  - `src/types/index.ts`
- Kept the first-pass types close to current frontend usage so later `.ts` / `.tsx` file conversions can adopt them incrementally.
- Added a small TODO in the group update types where the backend contract is not fully settled yet.

Notes:

- These types are additive only in this phase; existing JavaScript files were not converted yet.
- Media upload session shapes were included in `src/types/chat.ts` because that is already part of the current chat message flow.
- If backend payloads become stricter later, we should refine these interfaces at the service layer instead of spreading shape assumptions across components.
- `@types/node` was pinned to a TypeScript-4-compatible version because `react-scripts@5` currently keeps us on `typescript@4.9.x`; newer `@types/node` releases use syntax that this compiler version cannot parse. This pin is intentional and should be revisited when the frontend toolchain moves beyond CRA.

### Phase 3: Convert low-risk entry points

- Status: Implemented
- Renamed and converted:
  - `src/index.js` -> `src/index.tsx`
  - `src/App.js` -> `src/App.tsx`
  - `src/reportWebVitals.js` -> `src/reportWebVitals.ts`
- Wired `App.tsx` to shared types from Phase 2 (`AuthState`, `ThemeId`, `ResolvedTheme`).
- Added a null check for the root DOM element in `index.tsx`.
- Left page, service, and component files in JavaScript for later phases.

Notes:

- `App.test.js` still imported `./App` and continued to work without changes in this phase (converted in Phase 7).
- `theme/tokens.js` remained JavaScript until Phase 7.
- Verification:
  - `tsc -p chat-app-frontend/tsconfig.json --noEmit` passes
  - `npm run build` passes
  - `CI=true npm test -- --watchAll=false --runInBand` passes

### Phase 4: Convert service boundaries

- Status: Implemented
- Renamed and converted:
  - `src/services/api.js` -> `src/services/api.ts`
  - `src/services/websocket.js` -> `src/services/websocket.ts`
  - `src/components/chat-area/mediaUtils.js` -> `src/components/chat-area/mediaUtils.ts`
- Wired API and WebSocket functions to shared types from Phase 2.
- Added `SelectableUser` to `src/types/groups.ts` for group-related API responses.
- Added typed upload helpers (`UploadHandle`, `UploadProgressOptions`) in `api.ts`.
- Made `subscribeToTopic` generic so topic callbacks can be typed at call sites.

### Phase 5: Convert shared runtime state

- Status: Implemented
- Renamed and converted:
  - `src/context/WebSocketProvider.js` -> `src/context/WebSocketProvider.tsx`
- Typed:
  - `WebSocketContextValue` context default and provider value
  - `ChatTopicSubscriptionEntry` for the active chat topic subscription
  - `PersonalSubscriptionEntry<GroupSummaryUpdate>` for persistent personal-queue subscriptions
  - `subscribeSingleGroup` callbacks as `ChatMessage`
  - `subscribeGroupUpdates` callbacks as `GroupSummaryUpdate`
  - `useWebSocket()` return type
- Extracted shared personal-subscription release logic into `releasePersonalSubscription` without changing reconnect/cleanup behavior.

### Phase 6: Convert page-level orchestration

- Status: Implemented
- Renamed and converted:
  - `src/pages/ChatPage.js` -> `src/pages/ChatPage.tsx`
  - `src/pages/LoginPage.js` -> `src/pages/LoginPage.tsx`
  - `src/pages/RegisterPage.js` -> `src/pages/RegisterPage.tsx`
- Typed page props, route params, chat/group state, message pagination cursor, and handler contracts.
- Introduced local page types such as `ChatRouteId` (`"public" | number`) and `GroupMessageCursor` in `ChatPage.tsx`.
- Wired pages to shared types (`ChatMessage`, `ChatGroup`, `ThemeId`, `ThemeOption`, `Unsubscribe`).

### Phase 7: Convert presentational components and tests

- Status: Implemented
- Renamed and converted (minimal typing for rename-friendly diffs):
  - `src/components/Sidebar.js` -> `src/components/Sidebar.tsx`
  - `src/components/CreateGroupModal.js` -> `src/components/CreateGroupModal.tsx`
  - `src/components/ChatArea.js` -> `src/components/ChatArea.tsx`
  - chat-area presentational files under `src/components/chat-area/` (header, composer, list, and related media UI)
  - group-details components under `src/components/group-details/`
  - `src/theme/tokens.js` -> `src/theme/tokens.ts` (wired to shared theme types)
  - `src/App.test.js` -> `src/App.test.tsx` (typed mocks; fixed `checkAuth` mock shape)
- Intentionally left in JavaScript for now (CRA/tooling bootstrap only):
  - `src/setupTests.js`
  - `src/setupProxy.js`
- Prefer filesystem rename + small type annotations so git rename detection stays high.
- Verification:
  - `tsc -p chat-app-frontend/tsconfig.json --noEmit` passes
  - `make test` passes (utils + components + App smoke test)

### Phase 8: Tighten constraints

- Status: Planned
- Reduce `any` usage.
- Enable stricter compiler options gradually (`strict`, `noImplicitAny`, etc.).
- Remove `allowJs` only when the remaining JavaScript surface is small enough not to block normal development.
- Convert the last CRA-only bootstrap files if still present:
  - `src/setupTests.js`
  - `src/setupProxy.js` (removed entirely once Vite proxy exists)
- Upgrade TypeScript beyond the CRA 4.9 pin once Phase 10/11 land.

### Phase 9: Adopt and enforce pnpm in `chat-app-frontend`

- Status: Planned
- Goal: standardize on the same package manager already used by `e2e/`, and block accidental `npm` / `yarn` installs in this package.
- Scope:
  - Add `packageManager` to `chat-app-frontend/package.json` (align version with `e2e/package.json`).
  - Replace `package-lock.json` with `pnpm-lock.yaml`.
  - Enforce pnpm at install time:
    - add a `preinstall` script using [`only-allow`](https://github.com/pnpm-only-allow/only-allow), e.g. `"preinstall": "npx only-allow pnpm"`
    - document that contributors should run `corepack enable` once if they use Node's Corepack integration
    - with `packageManager` set, Corepack-aware Node installs will also reject `npm install` / `yarn install` in this directory
  - Keep `chat-app-frontend/` as a **standalone package**. Do **not** add `pnpm-workspace.yaml` at repo root.
  - Replace **all** `npm` usage in frontend tooling/docs with `pnpm`:
    - `chat-app-frontend/Makefile` (`pnpm test`, `pnpm run …`)
    - `chat-app-backend/Makefile` targets such as `build.fe`, `run.fe`, `test.fe`
    - `chat-app-frontend/README.md`, `REACT_APP_GUIDE.md`, embedding docs as needed
  - `react-scripts` may still exist **temporarily** in this phase only until Phase 10/11 land; it is not a long-term dependency.
- Out of scope:
  - Vite migration (Phase 10)
  - removing `react-scripts` (Phases 10–11)
  - repo-root workspace
- Verification:
  - `pnpm install`
  - `npm install` in `chat-app-frontend/` fails with a clear `only-allow` message
  - `pnpm start` still works until Phase 10 replaces it with `pnpm dev`
  - `pnpm run build:spring` still works until Phase 10 switches the build script
  - `make test` in `chat-app-frontend` uses `pnpm`

Notes:

- Prefer a dedicated PR that only changes lockfiles, enforcement, scripts, Makefiles, and docs references.
- If MUI/Emotion peer resolution needs help under pnpm, add the smallest necessary `.npmrc` change and document why.
- `only-allow` is the primary guardrail; Corepack is a nice extra when enabled locally/CI, not a substitute for documenting `pnpm install`.

### Phase 10: Replace CRA dev/build with Vite

- Status: Planned
- Goal: replace `react-scripts start/build` with Vite while preserving current dev UX and Spring embed output.
- Scope:
  - Add `vite`, `@vitejs/plugin-react`, and `vite.config.ts`.
  - Move CRA entry HTML to Vite root `index.html` and point it at `src/index.tsx`.
  - Recreate dev proxy behavior currently in `src/setupProxy.js`:
    - `/ws` with WebSocket upgrade
    - `/api`, `/auth`, `/login`, `/logout`, `/register`
  - Replace scripts:
    - remove `react-scripts start`
    - `dev` -> `vite` (keep port 3000 if practical)
    - `build` -> `vite build`
    - `build:spring` -> `vite build && pnpm run copy-to-spring`
  - Remove CRA dev/build dependency on `react-scripts` in this phase. If tests still rely on CRA Jest temporarily, keep the package only until Phase 11 deletes it.
  - Update static asset handling for files under `public/`.
  - Keep output compatible with Spring Boot static hosting (SPA fallback still served by backend routing).
- Out of scope:
  - test runner migration (Phase 11)
  - deleting every leftover CRA file (Phase 12)
- Verification:
  - `pnpm dev` loads login/chat UI
  - REST calls and SockJS/WebSocket work through the Vite proxy
  - `pnpm run build:spring` produces working embedded frontend at `http://localhost:9010`
  - Manual smoke: login, open group chat, send message, open image preview

Notes:

- Vite fully replaces CRA for dev server and production bundling. There is no reason to keep `react-scripts` for `start`/`build` after this phase.
- This codebase currently has no `REACT_APP_*` env usage, so env renames to `VITE_*` are likely minimal.
- Decide explicitly whether production build output stays `build/` for compatibility with existing copy scripts, or moves to `dist/` with script updates in the same phase.

### Phase 11: Replace CRA tests with Vitest

- Status: Planned
- Goal: remove the last reason to keep `react-scripts`.
- Scope:
  - Add `vitest`, `@vitest/coverage-v8` (optional), and `jsdom` test environment.
  - Add `vitest.config.ts` (or configure through `vite.config.ts`) with:
    - `@testing-library/jest-dom` setup
    - path aliases matching Vite/tsconfig
    - test globs for `src/**/*.test.ts(x)`
  - Replace scripts:
    - `test` -> `vitest`
    - add `test:run` -> `vitest run` for CI/one-shot runs
  - Update `chat-app-frontend/Makefile` targets to call Vitest/`pnpm test:run` instead of CRA Jest flags.
  - Port existing tests with minimal behavior changes:
    - util tests under `src/utils/`
    - component tests such as `ImagePreview.test.tsx`
    - `App.test.tsx` smoke test
  - Replace or retire `src/setupTests.js`.
  - Remove `react-scripts` from `package.json` in this phase.
- Out of scope:
  - Playwright E2E in `e2e/` (stays separate)
- Verification:
  - `pnpm test:run` passes full suite
  - `make test`, `make test.utils`, `make test.components`, `make test.app` pass
  - watch mode (`pnpm test`) works for local development
  - `pnpm why react-scripts` reports nothing after dependency cleanup

Notes:

- Vitest fully replaces CRA's Jest integration. After this phase, CRA is gone from runtime tooling.
- Prefer `vi.mock` patterns over CRA-specific Jest hoisting surprises; keep mocks close to the tests that need them.
- Revisit `@testing-library/user-event` version during this phase; Vitest pairs better with newer user-event APIs.

### Phase 12: Remove CRA leftovers and align repo integration

- Status: Planned
- Goal: finish the toolchain migration and update all repo entry points.
- Scope:
  - Remove remaining CRA-only files and config:
    - `src/setupProxy.js`
    - `src/react-app-env.d.ts`
    - `package.json` fields only used by CRA (`eslintConfig`, `browserslist`) if no longer needed
  - Finalize build output/copy path for Spring embed (`build/` vs `dist/`) and update:
    - `copy-to-spring.sh`
    - `chat-app-backend/Makefile` `build.fe`, `run.local`, `run.fe`, `test.fe` (all `pnpm`, no `npm`)
    - docs such as `03_REACT_EMBEDDING_SOLUTIONS.md`
  - Upgrade TypeScript to a Vite-compatible 5.x release and tighten `tsconfig.json` (`moduleResolution`, `strict` follow-ups from Phase 8).
  - Update frontend docs/README with the new commands (`pnpm dev`, `pnpm test:run`, `pnpm run build:spring`).
- Verification:
  - clean clone -> `pnpm install` -> `pnpm dev` works
  - `make build.fe` from backend works
  - `make test` and backend `make test.fe` work
  - repo search shows no remaining `npm install`, `npm start`, `npm test`, or `react-scripts` references in frontend docs/Makefiles/scripts

Notes:

- Treat this as the cleanup PR after Phases 10 and 11 are stable; avoid mixing with feature work.
- Re-run E2E (`e2e/` Playwright) against both dev-proxy and embedded-static workflows once CRA is gone.

## Rollout Notes

- Prefer small PRs grouped by concern, not by file extension alone.
- Avoid mixing TypeScript migration with unrelated frontend behavior changes.
- Keep each phase buildable and testable before moving to the next one.
- If a file has unclear data contracts, add explicit TODOs rather than guessing field shapes silently.
- For Phases 9–12, keep package-manager, bundler, test-runner, and cleanup changes in separate reviewable PRs where possible.
- After Phase 9, treat `pnpm` as the only supported package manager for `chat-app-frontend/`.

## Tooling replacement summary

| Concern | Today (CRA) | After migration |
| --- | --- | --- |
| Package manager | npm | pnpm (`only-allow` enforced) |
| Dev server | `react-scripts start` | `pnpm dev` (Vite) |
| Production build | `react-scripts build` | `pnpm run build` (Vite) |
| Unit tests | `react-scripts test` | `pnpm test` / `pnpm test:run` (Vitest) |
| Dev proxy | `src/setupProxy.js` | `vite.config.ts` `server.proxy` |
| `react-scripts` | required | removed after Phase 11 |

## Future Higher-Scale Path

- Extract domain-oriented hooks after type contracts settle, for example:
  - `useAuth`
  - `useGroups`
  - `useMessages`
  - `useChatSubscriptions`
- Generate shared API types from backend contracts if the project later adopts OpenAPI or another schema source.
- Revisit bundle analysis and code-splitting once Vite is in place (route-based chunks, lazy-loaded group-details dialogs, etc.).
