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

# Implement a custom subscription registry in RabbitMQ without Spring's STOMP broker relay

## Approach 1: Hybrid - Simple Broker + Manual RabbitMQ Sync

Use Spring's simple broker for WebSocket handling, and manually sync subscriptions to RabbitMQ for cross-instance messaging.

### Architecture

```
Client → Spring WebSocket (Simple Broker) → Custom Handler → RabbitMQ (AMQP)
```

### Implementation Steps

**1. Create a Custom Message Broker Handler:**

```java
@Component
public class CustomRabbitMQBrokerHandler implements SimpMessagingTemplate.CustomBrokerMessageHandler {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // Track subscriptions: topic -> Set<sessionId>
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Set up RabbitMQ listener for incoming messages
        setupRabbitMQListener();
    }

    // Called when client subscribes
    public void handleSubscribe(String sessionId, String destination) {
        subscriptions.computeIfAbsent(destination, k -> ConcurrentHashMap.newKeySet())
                     .add(sessionId);

        // Sync subscription to RabbitMQ
        syncSubscriptionToRabbitMQ(destination, sessionId, true);
    }

    // Called when client unsubscribes
    public void handleUnsubscribe(String sessionId, String destination) {
        Set<String> subs = subscriptions.get(destination);
        if (subs != null) {
            subs.remove(sessionId);
        }

        // Sync unsubscription to RabbitMQ
        syncSubscriptionToRabbitMQ(destination, sessionId, false);
    }

    private void syncSubscriptionToRabbitMQ(String destination, String sessionId, boolean subscribe) {
        // Convert STOMP destination to RabbitMQ exchange/queue
        String exchange = convertDestinationToExchange(destination);
        String queue = "sub-" + sessionId + "-" + destination.replace("/", "-");

        if (subscribe) {
            // Declare exchange and queue, bind them
            rabbitTemplate.execute(channel -> {
                channel.exchangeDeclare(exchange, "topic", true);
                channel.queueDeclare(queue, false, false, false, null);
                channel.queueBind(queue, exchange, getRoutingKey(destination));
                return null;
            });
        } else {
            // Unbind and delete queue
            rabbitTemplate.execute(channel -> {
                channel.queueUnbind(queue, exchange, getRoutingKey(destination));
                channel.queueDelete(queue);
                return null;
            });
        }
    }

    // Publish message to RabbitMQ when sending to topic
    public void publishToRabbitMQ(String destination, Object payload) {
        String exchange = convertDestinationToExchange(destination);
        String routingKey = getRoutingKey(destination);

        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
    }

    // Listen to RabbitMQ and forward to WebSocket clients
    private void setupRabbitMQListener() {
        // Create a listener for each topic exchange
        // When message arrives from RabbitMQ, forward to local subscribers
    }

    private String convertDestinationToExchange(String destination) {
        // "/topic/public" -> "topic.public"
        return destination.replace("/", ".").substring(1);
    }

    private String getRoutingKey(String destination) {
        return "#"; // Match all routing keys for topic
    }
}
```

**2. Create a Channel Interceptor to Track Subscriptions:**

```java
@Component
public class CustomSubscriptionInterceptor implements ChannelInterceptor {

    @Autowired
    private CustomRabbitMQBrokerHandler brokerHandler;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null) {
            StompCommand command = accessor.getCommand();
            String sessionId = accessor.getSessionId();
            String destination = accessor.getDestination();

            if (StompCommand.SUBSCRIBE.equals(command) && destination != null) {
                brokerHandler.handleSubscribe(sessionId, destination);
            } else if (StompCommand.UNSUBSCRIBE.equals(command) && destination != null) {
                brokerHandler.handleUnsubscribe(sessionId, destination);
            }
        }

        return message;
    }
}
```

**3. Modify WebSocketConfig to use Simple Broker:**

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private CustomSubscriptionInterceptor subscriptionInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Use simple broker (in-memory)
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(subscriptionInterceptor);
    }
}
```

**4. Modify Controller to Publish to RabbitMQ:**

```java
@Controller
public class WebSocketController {

    @Autowired
    private CustomRabbitMQBrokerHandler brokerHandler;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload MessageRequest request) {
        // Process message
        MessageResponse response = processMessage(request);

        // Send to local subscribers (via simple broker)
        messagingTemplate.convertAndSend("/topic/public", response);

        // Also publish to RabbitMQ for other instances
        brokerHandler.publishToRabbitMQ("/topic/public", response);
    }
}
```

## Approach 2: Fully Custom - Direct AMQP Integration

Implement a custom message broker handler that directly uses RabbitMQ AMQP without Spring's STOMP support.

### Architecture:

```
Client → Custom WebSocket Handler → RabbitMQ (AMQP) → Custom Message Router
```

### Implementation:

**1. Create Custom Message Broker Handler:**

```java
@Component
public class CustomRabbitMQMessageBroker implements MessageHandler {

    private final ConnectionFactory connectionFactory;
    private Connection connection;
    private Channel channel;

    // Subscription registry: destination -> Set<WebSocketSession>
    private final Map<String, Set<WebSocketSession>> subscriptions = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws IOException {
        connectionFactory = new CachingConnectionFactory("localhost");
        connection = connectionFactory.createConnection();
        channel = connection.createChannel(false);

        // Set up consumer for each topic
        setupConsumers();
    }

    public void subscribe(WebSocketSession session, String destination) {
        subscriptions.computeIfAbsent(destination, k -> ConcurrentHashMap.newKeySet())
                     .add(session);

        // Create RabbitMQ queue for this subscription
        String queueName = createQueueForSubscription(session, destination);

        // Set up consumer
        setupConsumer(queueName, destination);
    }

    public void unsubscribe(WebSocketSession session, String destination) {
        Set<WebSocketSession> subs = subscriptions.get(destination);
        if (subs != null) {
            subs.remove(session);
        }

        // Delete RabbitMQ queue
        deleteQueueForSubscription(session, destination);
    }

    public void publish(String destination, Object message) throws IOException {
        String exchange = convertDestinationToExchange(destination);
        String routingKey = getRoutingKey(destination);

        // Declare exchange
        channel.exchangeDeclare(exchange, "topic", true);

        // Publish message
        byte[] body = objectMapper.writeValueAsBytes(message);
        channel.basicPublish(exchange, routingKey, null, body);
    }

    private void setupConsumers() {
        // For each active subscription, create a consumer
        subscriptions.forEach((destination, sessions) -> {
            sessions.forEach(session -> {
                String queueName = createQueueForSubscription(session, destination);
                setupConsumer(queueName, destination);
            });
        });
    }

    private void setupConsumer(String queueName, String destination) {
        try {
            channel.basicConsume(queueName, true, (consumerTag, delivery) -> {
                // When message arrives from RabbitMQ, forward to WebSocket
                byte[] body = delivery.getBody();
                Object message = objectMapper.readValue(body, Object.class);

                // Send to all local subscribers of this destination
                Set<WebSocketSession> localSubs = subscriptions.get(destination);
                if (localSubs != null) {
                    localSubs.forEach(session -> {
                        try {
                            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
                        } catch (IOException e) {
                            // Handle error
                        }
                    });
                }
            }, consumerTag -> {});
        } catch (IOException e) {
            // Handle error
        }
    }

    private String createQueueForSubscription(WebSocketSession session, String destination) {
        String queueName = "ws-" + session.getId() + "-" + destination.replace("/", "-");
        String exchange = convertDestinationToExchange(destination);

        try {
            channel.queueDeclare(queueName, false, false, false, null);
            channel.queueBind(queueName, exchange, getRoutingKey(destination));
        } catch (IOException e) {
            // Handle error
        }

        return queueName;
    }
}
```

**2. Create Custom WebSocket Handler:**

```java
@Component
public class CustomWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private CustomRabbitMQMessageBroker messageBroker;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Handle connection
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Parse STOMP frame manually
        StompFrame frame = parseStompFrame(message.getPayload());

        if (frame.getCommand().equals("SUBSCRIBE")) {
            String destination = frame.getHeader("destination");
            messageBroker.subscribe(session, destination);
        } else if (frame.getCommand().equals("UNSUBSCRIBE")) {
            String destination = frame.getHeader("destination");
            messageBroker.unsubscribe(session, destination);
        } else if (frame.getCommand().equals("SEND")) {
            // Handle message sending
            handleSendMessage(session, frame);
        }
    }

    private void handleSendMessage(WebSocketSession session, StompFrame frame) {
        String destination = frame.getHeader("destination");
        String body = frame.getBody();

        // Process message
        Object response = processMessage(body);

        // Publish to RabbitMQ
        messageBroker.publish(destination, response);
    }
}
```

## Key Differences:

| Aspect                      | Spring STOMP Relay | Custom Implementation    |
| --------------------------- | ------------------ | ------------------------ |
| **Protocol**                | STOMP over TCP     | AMQP (or STOMP manually) |
| **Subscription Management** | Automatic          | Manual tracking          |
| **Message Routing**         | Automatic          | Manual routing logic     |
| **Complexity**              | Low                | High                     |
| **Control**                 | Limited            | Full control             |
| **Maintenance**             | Spring handles     | You handle               |

## When to Use Custom Implementation:

- Need AMQP instead of STOMP
- Require custom subscription logic (e.g., time-based, conditional)
- Need message transformation before routing
- Want to integrate with non-STOMP systems
- Require fine-grained control over queue/exchange management

## Should we use DirectExchange or TopicExchange for this app

### Implementation with DirectExchange

How it works:

- One exchange per destination:
  - `/topic/public` → `topic.public` exchange
  - `/topic/group.1` → `topic.group.1` exchange
  - `/topic/group.2` → `topic.group.2` exchange
- Routing: Empty routing key (`""`). All queues bound to an exchange receive all messages from that exchange.
- Bindings: Queue → Exchange with `""` routing key
- (Destination: a destination of the STOMP broker. FE can subscribe to it and receive message (via WebSocket))

Pros:

- Simple: no routing key logic
- Clear separation: **each destination has its own exchange**
- Easy to debug: one exchange per destination
- No wildcards needed

Cons:

- More exchanges: one per destination (can be many for many groups)
- Less flexible: cannot route based on patterns

### Alternative: TopicExchange

How it would work:

- One or a few exchanges for all destinations:
  - Single `topic.exchange` for all topics
  - Or separate `topic.public` and `topic.groups` exchanges
- Routing: Uses routing keys with wildcards:
  - `"public"` → `/topic/public`
  - `"group.1"` → `/topic/group.1`
  - `"group.*"` → all groups (wildcard)
- Bindings: Queue → Exchange with routing key pattern (e.g., `"public"`, `"group.1"`, `"group.*"`)

Pros:

- Fewer exchanges: can use **one exchange for all destinations**
- Flexible routing: wildcards (`*`, `#`) for pattern matching
- Can subscribe to multiple destinations with one binding (e.g., `"group.*"`)

Cons:

- More complex: routing key logic and pattern matching
- Harder to debug: routing depends on key patterns
- Potential mistakes: incorrect routing keys can cause missed messages

### Recommendation: DirectExchange

Reasons:

1. Simplicity: no routing key patterns to manage
2. Clarity: one exchange per destination is easy to understand
3. Fewer bugs: no wildcard matching errors
4. Sufficient for this use case: you don't need pattern-based routing
5. Exchange overhead is low: RabbitMQ handles many exchanges efficiently

When to use TopicExchange:

- You need pattern-based subscriptions (e.g., subscribe to all groups with `"group.*"`)
- You want fewer exchanges (though this is a minor benefit)
- You need complex routing rules

### Visual comparison

- DirectExchange (current):

```
/topic/public → topic.public (DirectExchange) → [queue1, queue2, queue3]
/topic/group.1 → topic.group.1 (DirectExchange) → [queue4, queue5]
/topic/group.2 → topic.group.2 (DirectExchange) → [queue6, queue7]
```

- TopicExchange (alternative):

```
All destinations → topic.exchange (TopicExchange)
  - Routing key "public" → [queues bound with "public"]
  - Routing key "group.1" → [queues bound with "group.1" or "group.*"]
  - Routing key "group.2" → [queues bound with "group.2" or "group.*"]
```

## Cleanup queue when users disconnect from Websocket

### Problem

- Queues are durable (survive RabbitMQ restarts) and not auto-deleted
- Queues are only deleted on explicit UNSUBSCRIBE
- If the app crashes or restarts, queues accumulate in RabbitMQ
- Each restart can create new queues while old ones remain

### Solution Implemented

1. Queue tracking: Added `sessionQueues` map to track all queues created per session

   ```java
   private final ConcurrentHashMap<String, Set<String>> sessionQueues = new ConcurrentHashMap<>();
   ```

2. Cleanup on WebSocket disconnect: When a client disconnects, all queues for that session are deleted

   - Added `cleanupSessionQueues()` method
   - Called from `WebSocketEventListener.handleWebSocketDisconnectListener()`

3. Cleanup on app shutdown: When the app shuts down gracefully, all queues created by this instance are deleted

   - Enhanced `@PreDestroy cleanup()` method
   - Added `cleanupAllQueues()` private method

4. Queue tracking: When a queue is created, it's added to `sessionQueues`; when deleted, it's removed from tracking

### How It Works Now

- Normal disconnect: Client disconnects → `SessionDisconnectEvent` → queues cleaned up
- Graceful shutdown: App shuts down → `@PreDestroy` → all queues cleaned up
- Crash/abrupt termination: Queues remain (limitation), but:
  - On next restart with same `instanceId`, old queues won't be reused (new sessionIds)
  - You can manually clean up old queues via RabbitMQ management UI
  - Or add a startup cleanup to remove queues with old instanceIds

### Note

If the app crashes (kill -9, system crash), `@PreDestroy` won't run, so queues remain. For production, consider:

- Using auto-delete queues (but they disappear on consumer disconnect)
- Adding TTL to queues
- Periodic cleanup job to remove orphaned queues
- Using a fixed `instanceId` and cleaning up on startup

The current solution handles normal disconnects and graceful shutdowns.

## Current (Redundant) Design: using each queue for each user/websocketSessionId

```
Exchange: topic.group.1
├─ Queue: ws.instance-1.session-123.topic.group.1 (routing key: "")
├─ Queue: ws.instance-1.session-456.topic.group.1 (routing key: "")
└─ Queue: ws.instance-2.session-789.topic.group.1 (routing key: "")

Message published to topic.group.1 → ALL queues receive it
```

Since all queues receive the same messages, per-session queues add no value.

### Simplified Design (One Queue Per Instance Per Destination)

```
Exchange: topic.group.1
├─ Queue: ws.instance-1.topic.group.1 (routing key: "")
└─ Queue: ws.instance-2.topic.group.1 (routing key: "")

Message published to topic.group.1 → Both queues receive it
```

Benefits:

- Simpler: one queue per instance per destination
- Less overhead: fewer queues to manage
- Same functionality: all messages still delivered
- Easier cleanup: one queue per destination instead of many

We should create:

- One queue per instance per destination: `ws.{instanceId}.{destination}`
- Example: `ws.instance-1.topic.group.1`, `ws.instance-2.topic.group.1`
- All sessions on the same instance subscribing to the same destination share one queue
- When a message arrives, check `localSubscriptions` to see which sessions should receive it

### Also: Use FanoutExchange Instead

Since all queues receive all messages, FanoutExchange is more appropriate than DirectExchange with empty routing key:

```java
// Current (works but not ideal):
DirectExchange + empty routing key = all queues get all messages

// Better:
FanoutExchange = designed for this exact use case (broadcast to all queues)
```

### Reference Counting for Queues

- Added `destinationSubscriptionCount` to track how many sessions subscribe to each destination
- Queue is created on first subscription
- Queue is deleted when the last subscription is removed
- Benefit: Queues are only created/deleted when needed

### Simplified Cleanup

- Removed per-session queue cleanup (queues are shared)
- Cleanup happens automatically when subscription count reaches 0
- Benefit: Less complex cleanup logic

### Architecture Now

```
Exchange: topic.group.1 (FanoutExchange)
├─ Queue: ws.instance-1.topic.group.1 (shared by all sessions on instance-1)
└─ Queue: ws.instance-2.topic.group.1 (shared by all sessions on instance-2)

Message published → Broadcast to all queues → Each instance receives once
```

# Scalability Assessment for the Approach 1: Hybrid - Simple Broker + Manual RabbitMQ Sync

- **Current fit**: Works well for a few thousand concurrent users on a handful of app instances.

  - Auth is session-based
  - WebSocket delivery rides the embedded SimpleBroker
  - RabbitMQ mirrors subscriptions for cross-instance fan-out
  - Persistence is single PostgreSQL instance with JPA.

- **Major scaling limits**:
  - SimpleBroker keeps all subscriptions and pending messages in each app node's memory; millions of connections would exhaust heap and CPU.
  - RabbitMQ fan-out per destination per instance adds lots of queues/bindings; **managing millions of dynamic queues is operationally heavy**.
  - HTTP session storage (with full `User` objects) doesn't scale or replicate efficiently for massive fleets.
  - Single PostgreSQL database for chat history + auth becomes a bottleneck without sharding/partitioning.
  - Lack of horizontal identity/session abstraction (no stateless tokens) makes global load balancing harder.

## Recommended Evolution Path

- **WebSocket layer**: Move to a dedicated STOMP-compatible broker (e.g., RabbitMQ STOMP plugin, ActiveMQ Artemis) or a managed **WebSocket gateway**. App servers would become stateless producers/consumers, offloading subscription tracking and message fan-out. For very high scale, consider **protocols like MQTT or custom gRPC streams instead of STOMP**.

- **Session/auth**: Replace HTTP session coupling with stateless JWT or opaque tokens stored in Redis. For WebSockets, use token-based handshake validation (e.g., `Authorization` header on `/ws`). This removes the need to serialize entire `User` objects and lets you load-balance freely.

- **Message flow**:

  - Adopt CQRS-style services: an API gateway for REST/WebSocket auth, a chat service for message ingestion, and a notification service for broadcasting.
  - ~~Messages go onto a durable log (Kafka/Pulsar). Consumers handle persistence, fan-out, and delivery to connected clients (via a WebSocket backplane or push service). This ensures backpressure handling and replay~~.
    - Nếu dùng kafka, thì KHÔNG tạo 1 topic cho mỗi group, như vậy sẽ là 10M groups, kafka KHÔNG thể handle được
    - Cũng không thể tạo 1 topic với 10M partition được
    - Kafka doesn't scale to millions of partitions per topic
    - Nếu chọn Kafka thì có thể nhiều group dùng chung 1 partition
    - Nhưng Kafka sẽ KHÔNG cần thiết cho case này. If you only need:
      - Simple message storage → DB is sufficient
      - Real-time delivery → Redis pub/sub is sufficient
      - No event streaming needs → Kafka adds complexity

- **Persistence**:

  - Partition chat history per group/user or use a scalable store (Cassandra, DynamoDB, Scylla) **optimized for append-heavy workloads**.
  - Keep PostgreSQL (or another relational DB) for metadata (users, groups) but shard/replicate as needed.

- **Cache & presence**: Introduce Redis or a purpose-built presence service to track online users, group memberships, throttling, etc., without hitting the DB.

- **Ops & observability**:
  - Containerize services and deploy on Kubernetes/nomad for auto-scaling.
  - Instrument metrics/tracing (Prometheus/OpenTelemetry) to watch queue depth, WS connection counts, DB load.
  - Plan for multi-region: replicate brokers, use geo routing, and ensure message ordering guarantees per group/room.

In short, go from "application node manages everything" to "stateless application tiers + dedicated messaging infrastructure + scalable storage". That's the path to millions of users.

## Will using Redis pub/sub unlock "millions of users" problem?

Maybe (see below). Both can relay plenty of messages, but they have different trade-offs:

- **Redis pub/sub** is fire-and-forget: no persistence, no acknowledgements, no queueing. Messages go only to clients connected at that moment. Scaling comes from clustering and sharding, but a single Redis node can still become a bottleneck if you push high fan-out traffic through it. Operationally it's simpler, but you lose durability, routing features, dead-letter handling, etc.

- **RabbitMQ** is a full broker: queues, persistent storage, back-pressure, routing options, acknowledgements, consumer groups. It's heavier but purpose-built for many producers/consumers. With clustering and mirrored queues, it already scales horizontally; the limiting factors are how you structure exchanges/queues and how many bindings you maintain.

For millions of users, the real bottlenecks are connection fan-out, state management, and persistence. Whether you use Redis pub/sub or RabbitMQ, you'll eventually need a dedicated WebSocket gateway or managed service, stateless auth, and a write-optimized message log (Kafka/Pulsar). Nhưng nếu chỉ tầm 1M user thì Redis pub/sub vẫn handle được

## Redis Pub/Sub's Approach

Redis pub/sub doesn’t have the same **queue management overhead**.

RabbitMQ's Queue Management Issue

- One queue per instance per destination
- Example: 100 groups × 10 instances = 1,000 queues
- Each queue needs: **creation, binding to exchange, tracking, cleanup** on unsubscribe
- At scale (thousands of groups, hundreds of instances), this becomes hundreds of thousands of queues to manage.

Redis Pub/Sub's Approach: uses channels, not queues

- Channels are just string identifiers (e.g., `"/topic/public"`, `"/topic/group.1"`)
- **No explicit creation** — channels exist when someone subscribes
- **No bindings** — publishers send to channel names directly
- **No cleanup** — channels disappear when no subscribers remain
- **Ephemeral and lightweight** — no persistent state to track (Tạm thời và nhẹ)

Math: with 1M users, each of them has 10 groups on average --> 10M active subscriptions

- Memory: Each subscription consumes memory. Rough estimate: **~100–200 bytes per subscription**. 10M subscriptions ≈ 1–2 GB just for subscription tracking.
- Network: Managing 10M subscriptions adds network overhead for subscribe/unsubscribe operations.
- Sharding: You’ll need Redis Cluster with multiple nodes to distribute the load.

For real-time chat where you want immediate delivery to online users and don't need offline queuing, **Redis pub/sub is simpler and avoids the queue management overhead**. For your use case, this is often a better fit than RabbitMQ's queue-per-instance-per-destination model.

## Overhead Calculation

Scenario Setup

- 1M users online
- Each user subscribes to 10 groups on average
- Total: 10M subscriptions
- 10M unique groups (destinations)
- 10 application instances

### RabbitMQ Overhead Calculation

Queue Management

- Queues created: 10M groups × 10 instances = 100M queues
- Each queue overhead:
  - Queue metadata: ~1–2 KB
  - Binding to exchange: ~500 bytes
  - Application tracking: ~200 bytes per queue
  - Total per queue: ~2–3 KB
- Total queue overhead: 100M × 2.5 KB ≈ 250 GB (just metadata)

Subscription Tracking

- Per-instance subscriptions: 1M users ÷ 10 instances × 10 groups = 1M subscriptions per instance
- RabbitMQ tracks each subscription internally
- Memory per subscription: ~100–200 bytes
- Total subscription memory: 10M × 150 bytes ≈ 1.5 GB

Exchange Management

- Exchanges: 10M exchanges (one per group)
- Exchange overhead: ~1–2 KB per exchange
- Total exchange overhead: 10M × 1.5 KB ≈ 15 GB

Binding Management

- Bindings: 100M bindings (one per queue)
- Binding overhead: ~500 bytes per binding
- Total binding overhead: 100M × 500 bytes ≈ 50 GB

Operational Overhead

- Queue lifecycle: Create/delete 100M queues on startup/shutdown
- Binding operations: 100M bindings to manage
- Exchange management: 10M exchanges
- Network overhead: Massive protocol overhead for 100M queues
- Monitoring: Tracking 100M queues is impractical

Total RabbitMQ Overhead

- Queue metadata: ~250 GB
- Subscription tracking: ~1.5 GB
- Exchange overhead: ~15 GB
- Binding overhead: ~50 GB
- Total: ~316.5 GB + operational overhead

### Redis Pub/Sub Overhead Calculation

Channel Management

- Channels: 10M unique channels (one per group)
- Channel overhead: ~50–100 bytes per channel (just a string key)
- Total channel overhead: 10M × 75 bytes ≈ 750 MB

Subscription Tracking

- 10M subscriptions total
- Memory per subscription: ~100–150 bytes (subscriber reference)
- Total subscription memory: 10M × 125 bytes ≈ 1.25 GB

Operational Overhead

- No queue creation/deletion needed
- No bindings to manage
- Channels are ephemeral (auto-cleanup when empty)
- Simple subscribe/unsubscribe operations
- Minimal monitoring overhead

Total Redis Pub/Sub Overhead

- Channel metadata: ~750 MB
- Subscription tracking: ~1.25 GB
- Operational complexity: Low (no queues to manage)
- Total: ~2 GB + minimal operational overhead

### Comparison Summary

| Metric                 | RabbitMQ               | Redis Pub/Sub          | Difference          |
| ---------------------- | ---------------------- | ---------------------- | ------------------- |
| Memory (subscriptions) | ~1.5 GB                | ~1.25 GB               | Similar             |
| Queue/Channel overhead | ~250 GB (100M queues)  | ~750 MB (10M channels) | RabbitMQ 333x more  |
| Exchange overhead      | ~15 GB (10M exchanges) | N/A                    | RabbitMQ only       |
| Binding overhead       | ~50 GB (100M bindings) | N/A                    | RabbitMQ only       |
| Total infrastructure   | ~316.5 GB              | ~2 GB                  | RabbitMQ 158x more  |
| Operational complexity | Extremely high         | Low                    | RabbitMQ much worse |
| Setup/teardown time    | Hours (100M queues)    | Instant                | RabbitMQ much worse |

### The Real Problem with RabbitMQ (10M Groups)

At this scale, RabbitMQ becomes impractical:

1. 100M queues to create, bind, monitor, and clean up

   - Startup time: Hours to create 100M queues
   - Shutdown time: Hours to delete 100M queues
   - Memory: 250 GB just for queue metadata

2. 10M exchanges to manage

   - 15 GB memory overhead
   - Complex routing table management

3. 100M bindings to maintain

   - 50 GB memory overhead
   - Binding operations are expensive

4. Operational impossibility

   - Management UI can't handle 100M queues
   - Monitoring tools will crash
   - Cluster coordination becomes a bottleneck

5. Network overhead
   - Each queue creation requires network round-trip
   - 100M operations = hours of setup time

### Redis Pub/Sub Advantages (10M Groups)

1. Channels are ephemeral

   - No explicit creation needed
   - Auto-cleanup when empty
   - 750 MB vs 250 GB (333x less memory)

2. No bindings

   - Direct channel-based routing
   - No 50 GB binding overhead

3. No exchanges

   - Simple string-based channels
   - No 15 GB exchange overhead

4. Instant operations
   - Subscribe/unsubscribe is O(1)
   - No queue lifecycle management

### Conclusion

With 10M unique groups, RabbitMQ is not suitable:

- Memory overhead: 316.5 GB vs 2 GB (158x difference)
- Operational complexity: Managing 100M queues is impractical
- Performance: Hours to start up vs instant
- Cost: Requires massive infrastructure

Redis pub/sub is the clear winner:

- 2 GB total overhead (vs 316.5 GB)
- Simple, scalable architecture
- No queue management needed
- Suitable for millions of channels

The math shows that RabbitMQ's queue-per-instance-per-destination model doesn't scale to millions of destinations. Redis pub/sub's channel-based model is far more efficient for this use case.

# Implement a custom subscription registry and use Redis pub/sub

## Important clarifications

1. Redis does not support STOMP — it uses its own pub/sub commands (PUBLISH/SUBSCRIBE).
2. Redis does not support AMQP — AMQP is RabbitMQ's protocol.
3. Redis pub/sub is fire-and-forget — it does not maintain persistent subscriptions like RabbitMQ, you must track it yourself (e.g., in Redis Sets).
4. You need a custom implementation because Spring's STOMP broker relay only works with STOMP-compatible brokers.
5. Use Spring's simple broker for local WebSocket connections and Redis pub/sub for cross-instance messaging.

## Can Redis store subscription registry?

Redis pub/sub does not maintain a subscription registry like RabbitMQ. Differences:

| Feature                    | RabbitMQ               | Redis Pub/Sub                      |
| -------------------------- | ---------------------- | ---------------------------------- |
| Subscription persistence   | Yes (stored in broker) | No (fire-and-forget)               |
| Protocol                   | STOMP or AMQP          | Redis commands (PUBLISH/SUBSCRIBE) |
| Message delivery guarantee | At-least-once          | Best-effort (no guarantee)         |
| Subscription tracking      | Automatic              | Manual (you must track)            |

## How Redis pub/sub works

```
Publisher → Redis Channel → All active subscribers receive message
```

- If no subscribers are listening, the message is lost
- Subscriptions are not persisted
- You must manually track who is subscribed

## Implementation: Redis pub/sub with Spring WebSocket (hybrid)

Since Spring's STOMP broker relay only works with STOMP-compatible brokers, you need a custom implementation.

### Architecture:

```
Client → Spring WebSocket (Simple Broker) → Custom Handler → Redis Pub/Sub
                                                              ↓
                                                    (Manual subscription tracking)
```

Note: this is a hybrid approach

- `config.enableSimpleBroker("/topic");`: This creates in-memory subscription registry!
- `SimpleBrokerMessageHandler` maintains subscriptions in a `ConcurrentHashMap` in memory
- Each instance has its own in-memory registry (which makes server stateful)
- Redis is only used for **cross-instance message distribution**, not subscription management

### Step 1: Add Redis Dependency: `spring-boot-starter-data-redis`

### Step 2: Create Redis Subscription Registry

```java
@Component
public class RedisSubscriptionRegistry {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String SUBSCRIPTION_KEY_PREFIX = "ws:subscriptions:";
    private static final String INSTANCE_SUBSCRIPTIONS_KEY = "ws:instance:";

    // Track subscriptions in Redis: topic -> Set<instanceId:sessionId>
    public void subscribe(String instanceId, String sessionId, String destination) {
        String key = SUBSCRIPTION_KEY_PREFIX + destination;
        String value = instanceId + ":" + sessionId;

        // Add to Redis set
        redisTemplate.opsForSet().add(key, value);

        // Also track per instance
        String instanceKey = INSTANCE_SUBSCRIPTIONS_KEY + instanceId;
        redisTemplate.opsForSet().add(instanceKey, destination);
    }

    public void unsubscribe(String instanceId, String sessionId, String destination) {
        String key = SUBSCRIPTION_KEY_PREFIX + destination;
        String value = instanceId + ":" + sessionId;

        // Remove from Redis set
        redisTemplate.opsForSet().remove(key, value);
    }

    public Set<String> getSubscribers(String destination) {
        String key = SUBSCRIPTION_KEY_PREFIX + destination;
        return redisTemplate.opsForSet().members(key);
    }

    // Get all topics this instance is subscribed to
    public Set<String> getInstanceSubscriptions(String instanceId) {
        String instanceKey = INSTANCE_SUBSCRIPTIONS_KEY + instanceId;
        return redisTemplate.opsForSet().members(instanceKey);
    }
}
```

### Step 3: Create Redis Message Publisher

```java
@Component
public class RedisMessagePublisher {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String CHANNEL_PREFIX = "ws:channel:";

    public void publish(String destination, Object message) {
        String channel = CHANNEL_PREFIX + destination;
        redisTemplate.convertAndSend(channel, message);
    }
}
```

### Step 4: Create Redis Message Subscriber

```java
@Component
public class RedisMessageSubscriber implements MessageListener {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private RedisSubscriptionRegistry subscriptionRegistry;

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(pattern);
        String destination = extractDestination(channel);

        // Check if this instance has subscribers for this destination
        Set<String> subscribers = subscriptionRegistry.getSubscribers(destination);
        boolean hasLocalSubscribers = subscribers.stream()
            .anyMatch(sub -> sub.startsWith(instanceId + ":"));

        if (hasLocalSubscribers) {
            // Deserialize message
            Object payload = deserializeMessage(message);

            // Forward to local WebSocket subscribers via simple broker
            messagingTemplate.convertAndSend(destination, payload);
        }
    }

    private String extractDestination(String channel) {
        // "ws:channel:/topic/public" -> "/topic/public"
        return channel.replace("ws:channel:", "");
    }

    private Object deserializeMessage(Message message) {
        // Deserialize Redis message to your message object
        // Implementation depends on your serialization strategy
        return new String(message.getBody());
    }
}
```

### Step 5: Configure Redis Message Listener

```java
@Configuration
public class RedisConfig {

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private RedisMessageSubscriber messageSubscriber;

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Subscribe to all WebSocket channels
        // You'll need to dynamically add channels as subscriptions occur
        container.addMessageListener(messageSubscriber,
            new PatternTopic("ws:channel:*"));

        return container;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setDefaultSerializer(new GenericJackson2JsonRedisSerializer());
        template.setKeySerializer(new StringRedisSerializer());
        return template;
    }
}
```

### Step 6: Create Subscription Interceptor

```java
@Component
public class RedisSubscriptionInterceptor implements ChannelInterceptor {

    @Autowired
    private RedisSubscriptionRegistry subscriptionRegistry;

    @Value("${spring.application.instance-id:${random.uuid}}")
    private String instanceId;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null) {
            StompCommand command = accessor.getCommand();
            String sessionId = accessor.getSessionId();
            String destination = accessor.getDestination();

            if (StompCommand.SUBSCRIBE.equals(command) && destination != null) {
                // Register subscription in Redis
                subscriptionRegistry.subscribe(instanceId, sessionId, destination);
            } else if (StompCommand.UNSUBSCRIBE.equals(command) && destination != null) {
                // Unregister subscription from Redis
                subscriptionRegistry.unsubscribe(instanceId, sessionId, destination);
            }
        }

        return message;
    }
}
```

### Step 7: Modify WebSocketConfig

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private RedisSubscriptionInterceptor subscriptionInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Use simple broker for local WebSocket connections
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(subscriptionInterceptor);
    }
}
```

### Step 8: Modify Controller to Publish to Redis

```java
@Controller
public class WebSocketController {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private RedisMessagePublisher redisPublisher;

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload MessageRequest request) {
        // Process message
        MessageResponse response = processMessage(request);

        // Send to local subscribers (via simple broker)
        messagingTemplate.convertAndSend("/topic/public", response);

        // Publish to Redis for other instances
        redisPublisher.publish("/topic/public", response);
    }
}
```

### Key differences: Redis vs RabbitMQ

| Aspect                | RabbitMQ (STOMP)      | Redis Pub/Sub                  |
| --------------------- | --------------------- | ------------------------------ |
| Subscription registry | Automatic (in broker) | Manual (you track in Redis)    |
| Protocol              | STOMP/AMQP            | Redis commands                 |
| Message persistence   | Optional              | No (fire-and-forget)           |
| Delivery guarantee    | At-least-once         | Best-effort                    |
| Spring support        | Built-in STOMP relay  | Custom implementation required |
| Complexity            | Low (with Spring)     | High (custom code)             |

## Stateless Redis Implementation

To make it stateless, you need to:

1. Not use `enableSimpleBroker()`
2. Store all subscriptions in Redis
3. Implement a custom message handler that reads from Redis

Here's how to make it truly stateless:

### Step 1: Don't Use Simple Broker

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // DON'T use enableSimpleBroker() - that's stateful!
        // Only set application destination prefix
        config.setApplicationDestinationPrefixes("/app");
        // No broker configured - we'll handle it manually
    }
}
```

### Step 2: Custom Message Handler (Stateless)

```java
@Component
public class StatelessRedisMessageHandler implements MessageHandler {

    @Autowired
    private RedisSubscriptionRegistry subscriptionRegistry;

    @Autowired
    private RedisMessagePublisher redisPublisher;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Value("${spring.application.instance-id}")
    private String instanceId;

    // This replaces SimpleBrokerMessageHandler
    @Override
    public void handleMessage(Message<?> message) throws MessagingException {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        String destination = accessor.getDestination();

        if (destination != null && destination.startsWith("/topic/")) {
            // Check Redis for subscribers (stateless - no local HashMap)
            Set<String> allSubscribers = subscriptionRegistry.getSubscribers(destination);

            // Filter local subscribers for this instance
            Set<String> localSubscribers = allSubscribers.stream()
                .filter(sub -> sub.startsWith(instanceId + ":"))
                .collect(Collectors.toSet());

            if (!localSubscribers.isEmpty()) {
                // Forward to local WebSocket clients
                Object payload = message.getPayload();
                messagingTemplate.convertAndSend(destination, payload);
            }

            // Always publish to Redis for other instances
            redisPublisher.publish(destination, message.getPayload());
        }
    }
}
```

### Step 3: Register Custom Handler

```java
@Configuration
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private StatelessRedisMessageHandler customMessageHandler;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // No simple broker - we use custom handler
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Register custom message handler
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Bean
    public BrokerMessageHandler brokerMessageHandler() {
        return customMessageHandler;
    }
}
```

### Step 4: Subscription Tracking (All in Redis)

```java
@Component
public class RedisSubscriptionRegistry {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String SUBSCRIPTION_KEY = "ws:subs:";

    // All subscriptions stored in Redis (stateless)
    public void subscribe(String instanceId, String sessionId, String destination) {
        String key = SUBSCRIPTION_KEY + destination;
        String value = instanceId + ":" + sessionId;
        redisTemplate.opsForSet().add(key, value);
    }

    public void unsubscribe(String instanceId, String sessionId, String destination) {
        String key = SUBSCRIPTION_KEY + destination;
        String value = instanceId + ":" + sessionId;
        redisTemplate.opsForSet().remove(key, value);
    }

    // Read from Redis (no local state)
    public Set<String> getSubscribers(String destination) {
        String key = SUBSCRIPTION_KEY + destination;
        return redisTemplate.opsForSet().members(key);
    }
}
```

### Comparison Table

| Aspect                   | Stateful Solution                | Stateless Solution           |
| ------------------------ | -------------------------------- | ---------------------------- |
| **Simple Broker**        | ✅ Used (`enableSimpleBroker()`) | ❌ Not used                  |
| **Subscription Storage** | In-memory HashMap + Redis        | Redis only                   |
| **Server State**         | Stateful (per-instance HashMap)  | Stateless (all in Redis)     |
| **Complexity**           | Lower (uses Spring's broker)     | Higher (custom handler)      |
| **Scalability**          | Limited (per-instance state)     | Full (shared state in Redis) |

### Why We use Simple Broker in Previous Solution

For simplicity:

- Spring's simple broker handles WebSocket message delivery automatically
- Redis was only for cross-instance message distribution
- Easier to implement

But this makes it stateful.
