# Only one WebSocket connection

## Current Architecture (Already Good!)

Actually, **you're already using one WebSocket connection** for all groups:

1. `connect()` is called once on mount → creates single `stompClient`
2. When connecting, it subscribes to `/topic/public`
3. When switching groups, it **subscribes to that group's topic** on the same connection
4. The check in `connectWebSocket()` reuses existing connection: `if (stompClient?.connected) return stompClient`

**So no performance issue here** — one connection, multiple subscriptions.
