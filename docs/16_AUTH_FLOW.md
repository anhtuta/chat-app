# Auth Login Flow (Session-Based)

## Intro

Login auth in this app is **server-side HTTP sessions** backed by **Redis** (Spring Session), not JWT. The browser holds only an **opaque session cookie**; Spring Security restores the principal from that session on each request.

This doc covers **user login auth only** (not group roles/permissions).

## Summary

- Opaque session cookie (not JWT); session data in Redis via Spring Session
- Login sets `SecurityContext` + session attrs (`SPRING_SECURITY_CONTEXT`, `user`)
- FE sends the cookie (`credentials: "include"`); Spring Security restores auth and requires `.authenticated()` on `/api/**` and `/ws/**`
- Single app role `ROLE_USER` is assigned but not checked with `hasRole`
- Controllers resolve the current user from `session.getAttribute("user")`

## High-level flow

```mermaid
sequenceDiagram
    participant FE as Frontend
    participant Auth as AuthController
    participant AuthSvc as AuthService
    participant Redis as Redis (Spring Session)
    participant Sec as Spring Security filter chain
    participant API as Protected /api/**

    Note over FE,Redis: Login
    FE->>Auth: POST /api/auth/login {username, password}
    Auth->>AuthSvc: verify credentials (BCrypt)
    AuthSvc-->>Auth: User entity
    Auth->>Auth: set SecurityContext + session attrs<br/>(SPRING_SECURITY_CONTEXT, user)
    Auth->>Redis: persist session
    Auth-->>FE: 200 + Set-Cookie (opaque session id)

    Note over FE,API: Authenticated API call
    FE->>Sec: GET/POST /api/... + Cookie
    Sec->>Redis: load session by cookie id
    Sec->>Sec: restore SecurityContext, require authenticated
    Sec->>API: forward if OK
    API->>API: User user = session.getAttribute("user")
    API-->>FE: response

    Note over FE,Redis: Logout
    FE->>Auth: POST /api/auth/logout + Cookie
    Auth->>Auth: clear SecurityContext
    Auth->>Redis: invalidate session
    Auth-->>FE: 200 + clear cookie
```

## WebSocket (brief)

Handshake is still an HTTP request with the same session cookie. `WebSocketHandshakeInterceptor` copies `"user"` from the HTTP session into WebSocket session attributes. Later STOMP messages rely on that attribute (plus `/ws/**` requiring authentication at connect time).

## What lives where

| Location       | Contents                                                          |
| -------------- | ----------------------------------------------------------------- |
| Browser cookie | Opaque session id only (not JWT, no claims)                       |
| Redis session  | `SPRING_SECURITY_CONTEXT`, `user`, other session attrs            |
| Request thread | `SecurityContextHolder` (restored per request by Spring Security) |
