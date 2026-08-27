## Current Problem

HTTP APIs returned JSON on success but **plain strings** on most 4xx failures. The 500 catch-all already returned a JSON map. The SPA often called `response.json()` (login/register) or `response.text()` (group APIs), so clients either threw on parse or showed raw JSON.

## Examples (status quo — before the fix)

- `POST /api/auth/login` with a wrong password: **401** body `Invalid username or password` (string). Frontend `response.json()` failed and the login page showed “An error occurred. Please try again.”
- `POST /api/auth/register` with a taken username: **400** body `Username already exists` (string). Same parse/catch problem.
- Concurrent message edit: **409** body as a string. Group APIs used `response.text()`, which worked only because the body was not JSON.
- Unexpected server errors: **500** JSON `{ timestamp, status, error, message, path }`. `response.text()` would show the whole blob.

## Possible Solutions

### 1. Envelope every success as `{ success, message, data }`

- How it works: wrap every 2xx payload in one envelope.
- Pros: one parse path for success and failure.
- Cons: large breaking change; chat payloads (`MessageResponse`, group lists) and WebSocket events would no longer match REST; auth already has a one-off `AuthResponse`.
- Recommendation for our problem: No

### 2. One `ErrorResponse` JSON for all HTTP failures (selected)

- How it works: keep resource-shaped 2xx bodies. Controller exceptions and Spring Security auth failures both return `{ timestamp, status, error, message, path }`. FE reads `message`.
- Pros: small contract change; matches the existing 500 shape; HTTP status stays the machine-readable case (`401` vs `409`).
- Cons: clients must parse JSON on errors (they should anyway).
- Recommendation for our problem: Yes

## Recommendation

Do **not** wrap successful responses. Add `ErrorResponse` and use it from every HTTP `@ExceptionHandler`. Frontend extracts `message` and shows it.

## Implementation details

- Added `com.hello.chatapp.dto.ErrorResponse` with `timestamp`, `status`, `error` (HTTP reason phrase), `message` (user-facing), `path`.
- `GlobalExceptionHandler` returns `ResponseEntity<ErrorResponse>` for 400/401/403/404/409/500. 500 still uses a generic message (no JDBC/Hibernate leakage).
- `SecurityConfig` now writes the same `ErrorResponse` JSON for protected `/api/**` requests rejected before controllers run (for example missing session cookie).
- SPA: `login`/`register` return `response.json()` as-is. Other APIs use `response.json()` and read `ErrorResponse.message`. Only `401` triggers login redirect; `403` stays in-app so business-rule messages are visible.
- Tests: `GlobalExceptionHandlerTest` asserts JSON fields; `apiError.test.ts` covers FE parsing.

Why it changed (why AI fixed it):

- Protected `/api/**` requests rejected by Spring Security could still bypass `GlobalExceptionHandler`, so they were not guaranteed to return the shared JSON `ErrorResponse`.
  - I fixed that in `chat-app-backend/src/main/java/com/hello/chatapp/config/SecurityConfig.java` by wiring JSON `401/403` handlers through a shared writer in `chat-app-backend/src/main/java/com/hello/chatapp/config/ApiErrorResponseWriter.java`.
- The shared frontend path in `chat-app-frontend/src/services/api.ts` still redirected on every `403`, which would swallow valid business-rule errors like “banned”, “not a member”, or “transfer leadership first”.
  - It now redirects only on `401`, so `403` messages stay visible to the user.

## Examples (after the fix)

Wrong password:

```json
{
  "timestamp": "2026-08-25T16:00:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid username or password",
  "path": "/api/auth/login"
}
```

Login page shows **Invalid username or password**.

Protected API without a session:

```json
{
  "timestamp": "2026-08-25T16:05:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "User is not authenticated",
  "path": "/api/groups"
}
```

## Lesson (look back here)

Success and failure do not need the same envelope. They do need **one failure shape**. Mixing string 4xx with JSON 500 makes every client guess.

## Future Higher-Scale Path

If clients need to branch without parsing English text (i18n, retries), add a stable `code` field (`INVALID_CREDENTIALS`, `OPTIMISTIC_LOCK`) without changing HTTP status.
