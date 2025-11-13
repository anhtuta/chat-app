# WebSocket and Session

Explain the code

```java
@MessageMapping("/chat.addUser") // Receives a join message from the client
@SendTo("/topic/public") // Broadcasts the join message to all clients
@NonNull
public Message addUser(@Payload @NonNull Message message, SimpMessageHeaderAccessor headerAccessor) {
    // Add username in websocket session
    Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
    if (sessionAttributes != null && message.getSender() != null) {
        // Stores the username in the WebSocket session attributes
        sessionAttributes.put("username", message.getSender());
    }
    return message;
}
```

### Why use sessions?

The session is used to:

- Track user identity across the connection lifecycle
- Identify who disconnected
- Avoid sending the username in every message

### Current limitations

- Stateful: requires server-side session storage
- Not scalable: sessions are in-memory (lost on server restart)
- Redundant: username is already in every message payload
- No authentication: anyone can claim any username

## Alternatives

### Option 1: Stateless (send username in every message)

Simplest — already partially in place:

```java
@MessageMapping("/chat.send")
@SendTo("/topic/public")
@NonNull
public Message sendMessage(@Payload @NonNull Message message) {
    // Username is already in message.getSender()
    return messageRepository.save(message);
}

// Remove addUser method entirely, or make it stateless:
@MessageMapping("/chat.addUser")
@SendTo("/topic/public")
@NonNull
public Message addUser(@Payload @NonNull Message message) {
    // Just broadcast, no session storage needed
    return message;
}
```

For disconnect events, you'd need to track connections differently (e.g., a user registry).

### Option 2: JWT tokens (recommended for production)

Use JWT for authentication and user identity:

```java
@MessageMapping("/chat.send")
@SendTo("/topic/public")
@NonNull
public Message sendMessage(
    @Payload @NonNull Message message,
    SimpMessageHeaderAccessor headerAccessor) {

    // Extract username from JWT token in headers
    String username = extractUsernameFromToken(headerAccessor);
    message.setSender(username); // Override to prevent spoofing

    return messageRepository.save(message);
}
```

Benefits:

- Stateless and scalable
- Secure (prevents username spoofing)
- Works across multiple servers
- Standard authentication pattern

### Option 3: User registry (hybrid)

Maintain a lightweight in-memory or Redis-backed registry:

```java
@Component
public class UserRegistry {
    private final Map<String, String> sessionToUser = new ConcurrentHashMap<>();

    public void registerUser(String sessionId, String username) {
        sessionToUser.put(sessionId, username);
    }

    public String getUsername(String sessionId) {
        return sessionToUser.get(sessionId);
    }

    public void removeUser(String sessionId) {
        sessionToUser.remove(sessionId);
    }
}
```

# How Spring WebSocket manages connections

Spring WebSocket uses several internal data structures to track connections. Here's how it works:

### 1. WebSocket session registry

Spring maintains a `WebSocketSessionRegistry` that stores active WebSocket sessions. Internally, it uses a `ConcurrentHashMap`:

```java
// Simplified internal structure (Spring's actual implementation)
private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
// Key: Session ID (unique identifier)
// Value: WebSocketSession object
```

Each client connection gets:

- A unique session ID
- A `WebSocketSession` object stored in the registry
- Session attributes (like the username you're storing)

### 2. STOMP session management

With STOMP (your setup), Spring uses additional structures:

**a) STOMP Session Registry:**

```java
// Internal structure (simplified)
Map<String, SimpSession> sessions = new ConcurrentHashMap<>();
// Key: STOMP session ID
// Value: SimpSession (contains subscriptions, user info, etc.)
```

**b) Subscription Registry:**

```java
// Tracks which clients are subscribed to which topics
Map<String, Set<String>> topicSubscriptions = new ConcurrentHashMap<>();
// Key: Topic name (e.g., "/topic/public")
// Value: Set of session IDs subscribed to that topic
```

### 3. Simple broker subscription tracking

Your config uses `enableSimpleBroker("/topic")`, which creates a `org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler` that maintains:

```java
// Internal subscription map
Map<String, Map<String, SimpSubscription>> subscriptions = new ConcurrentHashMap<>();
// Outer key: Session ID
// Inner key: Subscription ID
// Value: SimpSubscription (contains destination, session, etc.)
```

### Visual representation

```
Client Connection Flow:
┌─────────────┐
│   Browser   │
└──────┬──────┘
       │ WebSocket Handshake
       ▼
┌─────────────────────────────────────┐
│  WebSocketSessionRegistry           │
│  ┌──────────────────────────────┐   │
│  │ ConcurrentHashMap            │   │
│  │ sessionId → WebSocketSession │   │
│  └──────────────────────────────┘   │
└─────────────────────────────────────┘
       │
       │ STOMP CONNECT
       ▼
┌─────────────────────────────────────┐
│  SimpSessionRegistry                │
│  ┌──────────────────────────────┐   │
│  │ ConcurrentHashMap            │   │
│  │ sessionId → SimpSession      │   │
│  └──────────────────────────────┘   │
└─────────────────────────────────────┘
       │
       │ SUBSCRIBE /topic/public
       ▼
┌─────────────────────────────────────┐
│  SimpleBrokerMessageHandler         │
│  ┌──────────────────────────────┐   │
│  │ Map<SessionId, Subscriptions>│   │
│  │ "/topic/public" → [sess1,    │   │
│  │                   sess2, ...]│   │
│  └──────────────────────────────┘   │
└─────────────────────────────────────┘
```

### Accessing sessions programmatically

You can access the session registry if needed:

```java
@Autowired
private SimpUserRegistry userRegistry;  // For user-based sessions

@Autowired
private SimpMessagingTemplate messagingTemplate;

// Or access session attributes via SimpMessageHeaderAccessor
```

### Important points

1. Yes, Spring uses `ConcurrentHashMap` internally for thread safety
2. Sessions are in-memory by default (lost on server restart)
3. Each connection = one WebSocket session + one STOMP session
4. Subscriptions are tracked per session for efficient message routing

### Scalability consideration

With `enableSimpleBroker()` (in-memory), this works for a single server. For multiple servers, use an external broker like RabbitMQ or Redis, which maintains the subscription registry externally.

## Can We Configure Spring's Internal HashMap?

### ❌ **Limitation with Simple Broker**

With `enableSimpleBroker()`, Spring **does NOT automatically use** `org.springframework.messaging.simp.user.SimpUserRegistry`. The simple broker maintains its own internal subscription tracking that you **cannot directly configure**.

### ✅ **Option 1: Use External Broker (Recommended for Production)**

With RabbitMQ or Redis broker, you can fully configure the session registry:

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Bean
    public SimpUserRegistry customUserRegistry() {
        return new CustomSimpUserRegistry(); // Your custom implementation
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Use external broker instead of simple broker
        config.enableStompBrokerRelay("/topic")
              .setRelayHost("localhost")
              .setRelayPort(61613);
    }
}
```

### ✅ **Option 2: Access Spring's Internal Structures (Advanced)**

You can access Spring's internal structures through reflection or by injecting the actual beans:

```java
@Autowired
private SimpMessagingTemplate messagingTemplate;

// Access the underlying message handler
// (This is complex and not recommended)
```

### ✅ **Option 3: Keep Your Custom Registry (Current Approach - Recommended)**

**Your current approach is actually the BEST solution** because:

- ✅ Full control over the HashMap
- ✅ Works with simple broker
- ✅ Easy to extend and customize
- ✅ No dependency on Spring's internal implementation
- ✅ Can add custom logic (e.g., Redis persistence)

## Comparison

| Approach                   | Control    | Complexity | Works with Simple Broker   |
| -------------------------- | ---------- | ---------- | -------------------------- |
| **Your Custom Registry**   | ✅ Full    | ✅ Simple  | ✅ Yes                     |
| Spring's SimpUserRegistry  | ⚠️ Limited | ⚠️ Medium  | ❌ No (with simple broker) |
| External Broker + Registry | ✅ Full    | ❌ Complex | N/A                        |

# Session-based authentication (no JWT as requested)

Spring Security isn't recognizing manual session attributes. We need to set an `Authentication` in the `SecurityContext`.

1. **Creating proper Authentication object** - Using `UsernamePasswordAuthenticationToken` with user's username and `ROLE_USER` authority
2. **Setting SecurityContext** - Storing the authentication in Spring Security's `SecurityContext`
3. **Storing in session** - Using Spring Security's session key (`HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY`) to store the SecurityContext
4. **Updated checkAuth** - Now checks `SecurityContext` instead of manual session attributes

# Scaling to Multiple Instances

### Current Limitation

**Important:** The current implementation uses Spring's **in-memory message broker** (`SimpleBrokerMessageHandler`). This means:

- ❌ **Messages are NOT shared across instances**
- ❌ **Each instance maintains its own subscription registry**
- ❌ **Users connected to different instances cannot see each other's messages**

**Example Problem:**

```
Instance 1 (port 9010):
  - User A connected
  - User A sends message → Only User A sees it (if anyone else is on Instance 1)

Instance 2 (port 9011):
  - User B connected
  - User B sends message → Only User B sees it (if anyone else is on Instance 2)

❌ User A and User B cannot communicate!
```

Việc dùng in-memory broker sẽ lưu Subscription Registry ở memory, dẫn tới server stateful. Nếu **dùng RabbitMQ lưu Subscription Registry** thì sẽ khiến server stateless --> enabling horizontal scaling

### Solution: External Message Broker

To make multiple instances work together, you need an **external message broker** that all instances can connect to.

#### Option 1: RabbitMQ (Recommended)

Update `WebSocketConfig.java`:

```java
@Override
public void configureMessageBroker(MessageBrokerRegistry config) {
    // Use RabbitMQ as external broker instead of simple broker
    config.enableStompBrokerRelay("/topic")
          .setRelayHost("localhost")
          .setRelayPort(61613)
          .setClientLogin("guest")
          .setClientPasscode("guest");

    config.setApplicationDestinationPrefixes("/app");
}
```

All instances connect to the same RabbitMQ broker:

- Messages are shared across all instances
- Users on different instances can communicate
- Note: để đơn giản, ta dùng cái StompBrokerRelay có sẵn của Spring, và ta phải bật plugin `rabbitmq_stomp` của RabbitMQ. Tại sao phải dùng StompBrokerRelay thì check ở dưới

#### Option 2: Redis Pub/Sub

Configure Redis as message broker (requires custom implementation)

### Architecture with External Broker

```
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│ Instance 1  │      │ Instance 2  │      │ Instance 3  │
│  Port 9010  │      │  Port 9011  │      │  Port 9012  │
└──────┬──────┘      └──────┬──────┘      └──────┬──────┘
       │                    │                    │
       └────────────────────┴────────────────────┘
                           │
                    ┌──────▼──────┐
                    │   RabbitMQ  │
                    │  (or Redis) │
                    │   Broker    │
                    └─────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
   ┌────▼────┐        ┌────▼────┐        ┌────▼────┐
   │ Client A│        │ Client B│        │ Client C│
   │Instance1│        │Instance2│        │Instance3│
   └─────────┘        └─────────┘        └─────────┘

✅ All clients can communicate regardless of which instance they're connected to
```

# Message Flow with RabbitMQ and Multiple Instances

### Key Concept

**RabbitMQ acts as a central message hub** that all instances connect to. Instead of each instance maintaining its own subscription registry, RabbitMQ maintains a **shared subscription registry** that all instances can access.

### Key Differences from In-Memory Broker

| Aspect                    | In-Memory Broker                 | RabbitMQ Broker             |
| ------------------------- | -------------------------------- | --------------------------- |
| **Subscription Registry** | Each instance has its own        | Shared across all instances |
| **Message Routing**       | Only within same instance        | Across all instances        |
| **Scalability**           | ❌ Doesn't scale                 | ✅ Scales horizontally      |
| **Message Delivery**      | Only to clients on same instance | To clients on ANY instance  |

### Visual Flow: Complete Message Journey

```
┌─────────────────────────────────────────────────────────────┐
│ 1. User A (Instance 1) sends "Hello!" to group 1            │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
        ┌─────────────────────────────────┐
        │ Instance 1: WebSocketController │
        │ - Validates & saves to DB       │
        └─────────────────────────────────┘
                          │
                          ▼
        ┌─────────────────────────────────┐
        │ Instance 1 → RabbitMQ           │
        │ STOMP SEND: /topic/group.1      │
        └─────────────────────────────────┘
                          │
                          ▼
        ┌─────────────────────────────────┐
        │ RabbitMQ: Lookup subscribers    │
        │ /topic/group.1 subscribers:     │
        │   - Instance 1 (Client A)       │
        │   - Instance 2 (Client B)       │
        │   - Instance 3 (Client C)       │
        └─────────────────────────────────┘
                          │
            ┌─────────────┼─────────────┐
            │             │             │
            ▼             ▼             ▼
    ┌───────────┐  ┌───────────┐  ┌───────────┐
    │ Instance 1│  │ Instance 2│  │ Instance 3│
    │           │  │           │  │           │
    │ Client A  │  │ Client B  │  │ Client C  │
    │ (sender)  │  │           │  │           │
    └───────────┘  └───────────┘  └───────────┘
            │             │             │
            └─────────────┴─────────────┘
                          │
                          ▼
        ┌─────────────────────────────────┐
        │ All clients receive "Hello!"    │
        │ regardless of instance          │
        └─────────────────────────────────┘
```

### Important Points

1. **RabbitMQ is the single source of truth:**

   - All instances register their subscriptions with RabbitMQ
   - RabbitMQ maintains the complete subscription map

2. **Messages flow through RabbitMQ:**

   - When an instance sends a message, it goes to RabbitMQ first
   - RabbitMQ then distributes it to all subscribed instances
   - Each instance delivers to its own WebSocket clients

3. **No direct instance-to-instance communication:**

   - Instances don't talk to each other directly
   - **All communication goes through RabbitMQ**
   - This is why it works across different servers/machines

4. **Database is still shared:**
   - Messages are saved to PostgreSQL (shared database)
   - All instances can read historical messages
   - Real-time delivery happens through RabbitMQ

# Subscription Registry: In-Memory vs RabbitMQ

### In-Memory Broker (SimpleBrokerMessageHandler)

**It uses a HashMap (specifically `ConcurrentHashMap` for thread safety).**

When you use `enableSimpleBroker("/topic")`, Spring creates a `SimpleBrokerMessageHandler` that maintains an internal subscription registry:

```java
// Simplified internal structure of SimpleBrokerMessageHandler
public class SimpleBrokerMessageHandler {
    // Subscription registry: Topic → Set of Subscriptions
    private final Map<String, Set<SimpSubscription>> subscriptions = new ConcurrentHashMap<>();

    // Structure:
    // Key: Topic destination (e.g., "/topic/public", "/topic/group.1")
    // Value: Set of SimpSubscription objects (each represents a client subscription)
}
```

**How it works:**

1. **When a client subscribes:**

   ```
   Client subscribes to "/topic/group.1"
   → SimpleBrokerMessageHandler receives SUBSCRIBE command
   → Creates SimpSubscription object
   → Adds to subscriptions.get("/topic/group.1").add(subscription)
   ```

2. **When a message is sent:**

   ```
   Message sent to "/topic/group.1"
   → SimpleBrokerMessageHandler looks up: subscriptions.get("/topic/group.1")
   → Gets Set<SimpSubscription> for that topic
   → Iterates through subscriptions and sends message to each client
   ```

3. **Internal data structure:**
   ```
   subscriptions = {
     "/topic/public": [
       SimpSubscription(sessionId="sess-1", subscriptionId="sub-1"),
       SimpSubscription(sessionId="sess-2", subscriptionId="sub-2")
     ],
     "/topic/group.1": [
       SimpSubscription(sessionId="sess-1", subscriptionId="sub-3"),
       SimpSubscription(sessionId="sess-3", subscriptionId="sub-4")
     ]
   }
   ```

**Key Points:**

- ✅ **Yes, it's a HashMap** (`ConcurrentHashMap<String, Set<SimpSubscription>>`)
- ✅ **Yes, SimpleBrokerMessageHandler manages it automatically** - you don't need to configure it
- ✅ **Thread-safe** - uses `ConcurrentHashMap` for concurrent access
- ❌ **Per-instance** - each server instance has its own HashMap
- ❌ **Lost on restart** - all subscriptions are lost when server restarts

### RabbitMQ Broker (StompBrokerRelayMessageHandler)

**RabbitMQ does NOT use a HashMap in your application.**

When you use `enableStompBrokerRelay("/topic")`, Spring creates a `StompBrokerRelayMessageHandler` that:

1. **Does NOT maintain a local subscription registry**
2. **Forwards subscription commands to RabbitMQ**
3. **RabbitMQ maintains the subscription registry** (not in your app's memory)

**How it works:**

1. **When a client subscribes:**

   ```
   Client subscribes to "/topic/group.1"
   → StompBrokerRelayMessageHandler receives SUBSCRIBE command
   → Forwards STOMP SUBSCRIBE command to RabbitMQ
   → RabbitMQ records the subscription in its own internal storage
   → (No HashMap in your Spring application)
   ```

2. **When a message is sent:**

   ```
   Message sent to "/topic/group.1"
   → StompBrokerRelayMessageHandler receives message
   → Forwards STOMP SEND command to RabbitMQ
   → RabbitMQ looks up its own subscription registry
   → RabbitMQ forwards message to all subscribed instances
   → Each instance delivers to its WebSocket clients
   ```

3. **RabbitMQ's internal storage:**
   - RabbitMQ uses its own internal data structures (not HashMaps in your app)
   - Typically uses Erlang's ETS (Erlang Term Storage) or Mnesia database
   - Persists subscriptions (survives RabbitMQ restarts if configured)
   - Shared across all instances

**Key Points:**

- ❌ **No HashMap in your application** - subscriptions are managed by RabbitMQ
- ✅ **RabbitMQ manages subscriptions** - in its own internal storage (not Java HashMaps)
- ✅ **Shared across instances** - all instances see the same subscriptions
- ✅ **Persistent** - can survive RabbitMQ restarts (if configured)

### Summary

The key insight: **With RabbitMQ, your application doesn't maintain a subscription registry at all** - it just forwards subscription commands to RabbitMQ, and RabbitMQ maintains the registry in its own internal storage (which is not a Java HashMap).

### Additional Considerations for Multi-Instance

1. **Session Management:**

   - Current implementation uses in-memory HTTP sessions
   - For multiple instances, use **Spring Session with Redis**:
     ```xml
     <dependency>
         <groupId>org.springframework.session</groupId>
         <artifactId>spring-session-data-redis</artifactId>
     </dependency>
     ```

2. **Database:**

   - ✅ Already shared (PostgreSQL) - all instances use the same database
   - Messages are persisted and shared across instances

3. **Load Balancer:**
   - Use a load balancer (nginx, HAProxy) to distribute traffic
   - Configure sticky sessions for WebSocket connections (or use Redis sessions)

# Why Reactor Netty is needed for STOMP broker relay

When you use `enableStompBrokerRelay()`, Spring needs to establish a TCP connection from your Spring Boot app to RabbitMQ (port 61613 for STOMP). This is a separate server-to-server connection, not the client WebSocket connection.

**Architecture:**

```
Client ←→ [WebSocket/Tomcat] ←→ Spring Boot App ←→ [TCP/Reactor Netty] ←→ RabbitMQ
```

- Client ↔ Spring Boot: WebSocket handled by Tomcat (or your servlet container)
- Spring Boot ↔ RabbitMQ: TCP connection handled by Reactor Netty

`StompBrokerRelayMessageHandler` uses Reactor Netty as a TCP client to connect to RabbitMQ and forward STOMP commands (SUBSCRIBE, SEND, etc.).

## Can we use Tomcat instead?

No. Tomcat handles HTTP/WebSocket connections, not outbound TCP client connections. The broker relay needs a TCP client library to connect to RabbitMQ. Reactor Netty is a reactive TCP client library that Spring uses for this.

## What does the in-memory broker use?

The in-memory broker (`enableSimpleBroker()`) uses Tomcat (or your servlet container). It doesn't need external TCP connections.

**Architecture:**

```
Client ←→ [WebSocket/Tomcat] ←→ Spring Boot App ←→ [In-Memory SimpleBroker]
```

- Everything happens in-memory within the same JVM
- No external broker connection needed
- No Reactor Netty needed
- Uses Tomcat's WebSocket support

## Summary

| Broker Type                                         | Transport for Client Connection | Transport for Broker Connection | Needs Reactor Netty? |
| --------------------------------------------------- | ------------------------------- | ------------------------------- | -------------------- |
| **In-Memory Broker** (`enableSimpleBroker()`)       | Tomcat WebSocket                | N/A (in-memory)                 | ❌ No                |
| **STOMP Broker Relay** (`enableStompBrokerRelay()`) | Tomcat WebSocket                | Reactor Netty TCP               | ✅ Yes               |

**Bottom line:** Reactor Netty is only needed for the server-to-server TCP connection to RabbitMQ. The client WebSocket connections still use Tomcat. The in-memory broker doesn't need Reactor Netty because it doesn't make external connections.

# STOMP broker relay is not mandatory

When you use `enableStompBrokerRelay()`, the **subscription registry is already stored in RabbitMQ**, not in the STOMP broker relay.

STOMP Broker Relay: connection mechanism (bridge/relay) that **forwards STOMP commands to RabbitMQ**

```
Client → Spring Boot → STOMP Broker Relay → RabbitMQ
                                    ↑
                            (Just a relay/bridge)
                                    ↓
                            Subscription Registry
                            (Stored in RabbitMQ)
```

When a client subscribes:

1. Spring Boot receives the SUBSCRIBE command
2. STOMP Broker Relay forwards it to RabbitMQ (via TCP port 61613)
3. RabbitMQ stores the subscription in its registry (not in Spring Boot's memory)

## Can you avoid STOMP?

If you want to use RabbitMQ without STOMP:

- Option 1: Use AMQP directly

  - Use RabbitMQ's native AMQP protocol instead of STOMP
  - You can't use Spring's `@EnableWebSocketMessageBroker` STOMP support
  - You'd need to implement your own WebSocket message routing
  - More complex, but possible

- Option 2: Keep STOMP (recommended)
  - STOMP is the standard protocol for WebSocket messaging
  - Spring's WebSocket support is built around STOMP
  - The subscription registry is already in RabbitMQ
  - The STOMP broker relay is just the connection mechanism
