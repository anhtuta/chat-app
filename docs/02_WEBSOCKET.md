# Only one WebSocket connection

## Current Architecture (Already Good!)

Actually, **you're already using one WebSocket connection** for all groups:

1. `connect()` is called once on mount → creates single `stompClient`
2. When connecting, it subscribes to `/topic/public`
3. When switching groups, it **subscribes to that group's topic** on the same connection
4. The check in `connectWebSocket()` reuses existing connection: `if (stompClient?.connected) return stompClient`

**So no performance issue here** — one connection, multiple subscriptions.

# UNSUBSCRIBE in STOMP protocol

In the STOMP protocol specification, an `UNSUBSCRIBE` frame does not contain a destination header — it only transmits a unique `subscriptionId`. Consequently, when tracking UNSUBSCRIBE events on a Spring backend, calling `SimpMessageHeaderAccessor.getDestination()` will always return `null`.

For the core mechanics of the WebSocket connection, the server only needs the subscription ID to process the unsubscribe action.

To successfully identify which destination a user is unsubscribing from, you must map the `subscriptionId` to its corresponding destination when the client initially sends the `SUBSCRIBE` frame. See [CustomRabbitMQBrokerHandler.java](../chat-app-backend/src/main/java/com/hello/chatapp/config/CustomRabbitMQBrokerHandler.java) for more details.

## What it looks like in raw STOMP frames

When a client initiates a subscription, it sends a frame like this (notice both the destination and the id are present):

```
SUBSCRIBE
id:sub-001
destination:/topic/updates
```

When that same client wants to stop listening, it only passes that id back to the server. The destination is completely omitted by design to keep the frame payload small:

```
UNSUBSCRIBE
id:sub-001
```

Ref:

- Google AI
- https://stackoverflow.com/questions/54658349/detect-destination-channel-of-sessionunsubscribeevent
- https://stackoverflow.com/questions/65386649/how-can-i-get-subscribe-destination-from-unsubscribe-frame-in-spring-websocket-s
- https://github.com/spring-projects/spring-framework/issues/26118
- https://stomp.github.io/stomp-specification-1.2.html
- https://github.com/spring-projects/spring-framework/issues/26118
