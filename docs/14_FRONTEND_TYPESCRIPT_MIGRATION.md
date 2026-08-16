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
4. Phase 4: Convert state-heavy files (`WebSocketProvider`, `ChatPage`) after shared types are stable.
5. Phase 5: Convert feature components and tests in small batches.
6. Phase 6: Tighten TypeScript rules gradually and remove `allowJs` when the migration is nearly complete.
7. Phase 7: Re-evaluate whether a later CRA-to-Vite migration is still worthwhile.

### Recorded Decision

- TypeScript comes first.
- Vite, if we adopt it, comes later as a separate tooling migration.
- We will keep the app as a React SPA for now and will not move to Next.js in this migration.
- We should only revisit Next.js if future requirements demand SSR, SSG, or a React-owned server layer that Spring Boot does not already cover.

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

- `App.test.js` still imports `./App` and continues to work without changes.
- `theme/tokens.js` remains JavaScript for now; `App.tsx` consumes its exports as-is.
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

- Reduce `any` usage.
- Enable stricter compiler options gradually.
- Remove `allowJs` only when the remaining JavaScript surface is small enough not to block normal development.

## Rollout Notes

- Prefer small PRs grouped by concern, not by file extension alone.
- Avoid mixing TypeScript migration with unrelated frontend behavior changes.
- Keep each phase buildable and testable before moving to the next one.
- If a file has unclear data contracts, add explicit TODOs rather than guessing field shapes silently.

## Future Higher-Scale Path

- Extract domain-oriented hooks after type contracts settle, for example:
  - `useAuth`
  - `useGroups`
  - `useMessages`
  - `useChatSubscriptions`
- Generate shared API types from backend contracts if the project later adopts OpenAPI or another schema source.
- Revisit a CRA-to-Vite migration once the frontend has stronger type safety and smaller refactor risk.
