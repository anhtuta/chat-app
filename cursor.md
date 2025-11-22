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

Analyzing the architecture and requirements to provide a consistent recommendation.

# Final recommendation: use Redis pub/sub for this use case. Kafka is not needed.

Scale to 1M DAU, each of them has 10 groups on average.

### Why Redis pub/sub fits

1. Messages are already persisted in the database
2. **Real-time delivery to online users is the main requirement**
3. No need for message persistence in the messaging layer
4. 10M groups require a lightweight channel model

### Why not Kafka

- Adds unnecessary complexity (Kafka → Consumer → Redis → WebSocket)
- Adds latency (extra hop)
- No event streaming or multiple consumers needed
- Overkill for real-time chat with DB persistence

## Architecture: Redis pub/sub

```
┌──────────────────────────────────────────────────────────┐
│ Current: SimpleBroker (local) + RabbitMQ (cross-instance)│
└──────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────┐
│ Proposed: SimpleBroker (local) + Redis Pub/Sub (cross)   │
└──────────────────────────────────────────────────────────┘
```

### Flow

1. User sends message → Controller saves to DB
2. Controller publishes to Redis channel (e.g., `ws:channel:/topic/group.1`)
3. All instances subscribed to that channel receive the message
4. Each instance forwards to local WebSocket subscribers via SimpleBroker

## Math: Redis pub/sub at scale

### Scenario

- 1M DAU (daily active users)
- 10M unique groups
- 10 application instances
- Average 10 groups per user

### Memory calculation

#### Channel overhead

- Channels: 10M unique channels (one per group)
- Channel metadata: ~50–100 bytes per channel (string key + minimal Redis overhead)
- Total: 10M × 75 bytes ≈ 750 MB

#### Subscription tracking

- Total subscriptions: 1M users × 10 groups = 10M subscriptions
- Per subscription: ~100–150 bytes (subscriber reference in Redis)
- Total: 10M × 125 bytes ≈ 1.25 GB

#### Redis pub/sub internal

- Redis tracks active subscribers per channel
- Memory per active subscription: ~100 bytes
- Total: 10M × 100 bytes ≈ 1 GB

#### Total Redis memory

- Channel metadata: ~750 MB
- Subscription tracking: ~1.25 GB
- Redis pub/sub internal: ~1 GB
- Total: ~3 GB

### Network calculation

#### Message throughput

- Assumptions:
  - Average 10 messages/user/day = 10M messages/day
  - Peak: 3x average = 30M messages/day
  - Peak hour: 20% of daily = 6M messages/hour = ~1,667 messages/second
  - Average message size: 500 bytes (JSON)

#### Bandwidth

- Peak throughput: 1,667 msg/s × 500 bytes = ~833 KB/s = ~6.7 Mbps
- Per instance: 6.7 Mbps ÷ 10 instances = ~0.67 Mbps per instance
- Well within Redis capabilities (Redis can handle 100K+ ops/sec)

### Operational overhead

#### Redis pub/sub

- No queue creation/deletion
- No bindings to manage
- Channels are ephemeral (auto-cleanup)
- Simple subscribe/unsubscribe operations
- Minimal monitoring overhead

#### Comparison with RabbitMQ (10M groups)

| Metric                 | RabbitMQ      | Redis Pub/Sub |
| ---------------------- | ------------- | ------------- |
| Memory                 | ~316.5 GB     | ~3 GB         |
| Queues/Channels        | 100M queues   | 10M channels  |
| Exchanges              | 10M exchanges | N/A           |
| Bindings               | 100M bindings | N/A           |
| Setup time             | Hours         | Instant       |
| Operational complexity | Very high     | Low           |

## Implementation plan

### Step 1: Create Redis pub/sub handler

```java
@Component
public class CustomRedisPubSubHandler {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Value("${spring.application.instance-id}")
    private String instanceId;

    private final RedisMessageListenerContainer container;

    // Publish message to Redis channel
    public void publishToRedis(String destination, Object payload) {
        String channel = "ws:channel:" + destination;
        redisTemplate.convertAndSend(channel, payload);
    }

    // Subscribe to Redis channel (called when first subscription to destination)
    public void subscribeToRedis(String destination) {
        String channel = "ws:channel:" + destination;
        // Redis pub/sub subscription is handled by MessageListener
    }
}
```

### Step 2: Create Redis message listener

```java
@Component
public class RedisMessageSubscriber implements MessageListener {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(pattern);
        String destination = channel.replace("ws:channel:", "");

        // Deserialize message
        Object payload = deserialize(message.getBody());

        // Forward to local WebSocket subscribers via SimpleBroker
        messagingTemplate.convertAndSend(destination, payload);
    }
}
```

### Step 3: Update WebSocketController

```java
@MessageMapping("/group.send")
public MessageResponse sendGroupMessage(...) {
    // Save to DB
    Message savedMessage = messageRepository.save(message);
    MessageResponse response = MessageResponse.fromMessage(savedMessage);

    String destination = "/topic/group." + group.getId();

    // Send to local subscribers via SimpleBroker
    messagingTemplate.convertAndSend(destination, response);

    // Publish to Redis for cross-instance distribution
    redisPubSubHandler.publishToRedis(destination, response);

    return response;
}
```

### Step 4: Configure Redis listener

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisMessageSubscriber subscriber) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Subscribe to all WebSocket channels
        container.addMessageListener(subscriber, new PatternTopic("ws:channel:*"));

        return container;
    }
}
```

## Scalability validation

### Redis cluster sizing

- Memory: ~3 GB (can fit in a single Redis instance, but use cluster for HA)
- Throughput: ~1,667 msg/s (well within Redis limits)
- Channels: 10M channels (Redis handles millions of channels efficiently)

### Redis cluster configuration

- 3–6 nodes (for HA and sharding)
- Each node: 4–8 GB RAM
- Total cluster: 12–48 GB RAM (plenty of headroom)

### Application instances

- 10 instances (can scale to 100+)
- Each instance: handles ~100K users
- Stateless (except SimpleBroker for local subscriptions)

## Conclusion

Redis pub/sub is the right choice:

- Memory: ~3 GB vs RabbitMQ’s ~316.5 GB (105x less)
- Operational: simple vs very complex
- Performance: instant operations vs hours of setup
- Cost: much lower infrastructure requirements
- Fit: matches the use case (real-time delivery, DB persistence)

Kafka is not needed because:

- You already persist to DB
- You don’t need event streaming
- You don’t need multiple consumers
- It adds unnecessary complexity and latency

Final architecture:

- SimpleBroker: local WebSocket subscriptions (in-memory, fast)
- Redis pub/sub: cross-instance message distribution (10M channels, efficient)
- PostgreSQL: message persistence (source of truth)

This architecture scales to millions of users efficiently.

# Scaling other parts

## 1. Database scaling: PostgreSQL vs Cassandra/MongoDB

### Current workload analysis

**Write throughput:**

- 1M DAU × 10 messages/day = 10M messages/day
- Peak: 3× average = 30M messages/day
- Peak hour: 20% of daily = 6M messages/hour = ~1,667 messages/second
- Average message size: ~200 bytes (content + metadata)
- Peak write bandwidth: 1,667 × 200 bytes = ~333 KB/s = ~2.7 Mbps

**Read throughput:**

- Users fetch recent messages when opening a group
- Assume 1M users open 1 group/day = 1M reads/day
- Peak: ~167 reads/second (much lower than writes)

### PostgreSQL assessment

**Can PostgreSQL handle this?**

- Yes, with proper tuning:
  - Connection pooling (HikariCP): 20–50 connections
  - Write-ahead logging (WAL) for durability
  - Indexes on `group_id`, `timestamp`, `user_id`
  - Partitioning by date (optional, for very large tables)

**PostgreSQL capacity:**

- Can handle 10K+ writes/second with proper hardware
- Your peak: ~1,667 writes/second (well within limits)
- Single PostgreSQL instance can handle this

**When to consider Cassandra/MongoDB:**

- If you need 100K+ writes/second
- If you need multi-region writes
- If you need automatic sharding
- If you need schema-less flexibility

**Recommendation:** Keep PostgreSQL for now. It can handle this workload. Consider Cassandra/MongoDB only if:

- You scale to 10M+ DAU
- You need multi-region writes
- You need automatic sharding

## 2. WebSocket gateway: Spring WebSocket vs dedicated gateway

### Current architecture analysis

**Spring WebSocket SimpleBroker:**

- Tracks subscriptions in `ConcurrentHashMap` per instance
- Each instance handles ~100K connections (1M users ÷ 10 instances)
- Memory per subscription: ~200 bytes
- Total per instance: 100K × 200 bytes = ~20 MB (subscriptions only)

**Connection overhead:**

- Each WebSocket connection: ~8–16 KB (TCP buffers + Spring overhead)
- Per instance: 100K connections × 12 KB = ~1.2 GB
- Total memory per instance: ~1.2 GB (connections) + ~20 MB (subscriptions) = ~1.25 GB

### Is Spring WebSocket sufficient?

**Yes, for 1M DAU:**

- 1M DAU ≠ 1M concurrent connections
- Typical concurrent ratio: 10–20% of DAU
- Expected concurrent: 100K–200K connections
- With 10 instances: 10K–20K connections per instance
- Memory per instance: 10K × 12 KB = ~120 MB (very manageable)

**When to use a dedicated gateway:**

- 10M+ concurrent connections
- Need advanced features (rate limiting, message queuing, etc.)
- Need protocol translation (STOMP → MQTT, etc.)
- Need specialized WebSocket infrastructure

**Dedicated gateway options:**

- Socket.IO cluster
- Pusher
- AWS API Gateway WebSocket
- Custom gateway (Go/Node.js)

**Recommendation:** Keep Spring WebSocket. It handles this scale. Consider a dedicated gateway only if:

- You need 10M+ concurrent connections
- You need advanced features
- You want to offload WebSocket management

## 3. Stateless auth: session-based vs JWT

### Current session analysis

**Current setup:**

- In-memory HTTP sessions (per instance)
- User object stored in session (~500 bytes per session)
- 1M DAU, assume 20% concurrent = 200K active sessions
- Per instance: 200K ÷ 10 = 20K sessions
- Memory per instance: 20K × 500 bytes = ~10 MB (sessions only)

**Problem with current setup:**

- Sessions are in-memory (**lost on restart**)
- Sticky sessions required (or sessions don't work across instances)
- No session sharing between instances

**Solution: Spring Session with Redis**

**Memory calculation:**

- 200K active sessions × 500 bytes = ~100 MB in Redis
- Redis can easily handle this
- Sessions shared across all instances

**JWT vs Redis sessions:**

| Aspect             | In-Memory Sessions      | Redis Sessions | JWT         |
| ------------------ | ----------------------- | -------------- | ----------- |
| **Stateless**      | ❌ No                   | ⚠️ Partially   | ✅ Yes      |
| **Multi-instance** | ❌ No (sticky sessions) | ✅ Yes         | ✅ Yes      |
| **Memory**         | Per instance            | Shared (Redis) | Client-side |
| **Revocation**     | ✅ Easy                 | ✅ Easy        | ❌ Hard     |
| **Complexity**     | Low                     | Medium         | Low         |
| **Scalability**    | Limited                 | High           | High        |

**Recommendation:** Use Spring Session with Redis (not JWT):

- Sessions work across instances
- Easy session revocation (logout)
- Simple to implement (just add dependency)
- No code changes needed (Spring handles it)

**Implementation:** Add `spring-session-data-redis` to pom.xml, then only need to config this:

```yaml
spring:
  session:
    store-type: redis
```

## 4. Cache layer: what to cache?

### Data analysis

**Messages:**

- Not worth caching individual messages (already in DB)
- Users fetch recent messages (last 50–100 per group)
- Cache recent messages per group for fast loading

**Group metadata:**

- Group info (name, description, etc.)
- Member lists
- Frequently accessed, rarely changed

**User info:**

- User profiles (username, fullname, avatar)
- Frequently accessed, rarely changed

**Presence/online status:**

- Who's online
- Changes frequently, but small data

### Cache strategy

**1. Recent messages cache (Redis):**

```
Key: "group:{groupId}:messages:recent"
Value: List<MessageResponse> (last 50 messages)
TTL: 1 hour (or until new message arrives)
```

**Memory calculation:**

- 10M groups × 50 messages × 200 bytes = ~100 GB (too much!)
- Better: Cache only active groups (groups with recent activity)
- Assume 1M active groups: 1M × 50 × 200 bytes = ~10 GB

**2. Group metadata cache (Redis):**

```
Key: "group:{groupId}:meta"
Value: Group info (name, description, member count)
TTL: 1 hour
```

**Memory calculation:**

- 10M groups × 500 bytes = ~5 GB
- Can cache all groups (reasonable)

**3. User info cache (Redis):**

```
Key: "user:{userId}:info"
Value: User profile (username, fullname, avatar URL)
TTL: 1 hour
```

**Memory calculation:**

- 1M users × 200 bytes = ~200 MB
- Can cache all users (very reasonable)

**4. Presence cache (Redis):**

```
Key: "user:{userId}:online"
Value: Boolean + timestamp
TTL: 5 minutes (heartbeat)
```

**Memory calculation:**

- 200K online users × 50 bytes = ~10 MB
- Very lightweight

### Total cache memory

| Cache Type                      | Memory     | Priority |
| ------------------------------- | ---------- | -------- |
| Recent messages (active groups) | ~10 GB     | High     |
| Group metadata                  | ~5 GB      | High     |
| User info                       | ~200 MB    | Medium   |
| Presence                        | ~10 MB     | Low      |
| **Total**                       | **~15 GB** |          |

**Recommendation:**

1. High priority: Group metadata cache (5 GB)
2. High priority: Recent messages cache for active groups (10 GB)
3. Medium priority: User info cache (200 MB)
4. Low priority: Presence cache (10 MB)

## Final recommendations summary

| Component     | Current            | Recommendation             | Priority  |
| ------------- | ------------------ | -------------------------- | --------- |
| **Database**  | PostgreSQL         | Keep PostgreSQL            | ✅ Keep   |
| **WebSocket** | Spring WebSocket   | Keep Spring WebSocket      | ✅ Keep   |
| **Auth**      | In-memory sessions | Add Spring Session + Redis | 🔴 High   |
| **Cache**     | None               | Add Redis cache layer      | 🟡 Medium |

### Architecture after changes

```
┌─────────────────────────────────────────────────────────┐
│ Application Instances (10)                              │
│ - Spring WebSocket (SimpleBroker for local)             │
│ - Redis Pub/Sub (cross-instance messaging)              │
│ - Spring Session + Redis (shared sessions)              │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ Redis Cluster                                           │
│ - Pub/Sub: 10M channels (~3 GB)                         │
│ - Sessions: 200K sessions (~100 MB)                     │
│ - Cache: Group metadata + recent messages (~15 GB)      │
│ Total: ~18 GB                                           │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ PostgreSQL                                              │
│ - Messages: 10M/day writes                              │
│ - Peak: ~1,667 writes/second                            │
│ - Well within PostgreSQL limits                         │
└─────────────────────────────────────────────────────────┘
```

This architecture scales to 1M DAU efficiently.

## Cache update strategies for recent messages

### Strategy 1: Update cache on every new message

**How it works:**

- When a new message arrives, append it to the cached list
- Remove oldest message if list exceeds limit (e.g., 50 messages)

**Math for talkative groups:**

- Very active group: 100 messages/minute = ~1.67 messages/second
- Cache update operations: 1.67 Redis writes/second per group
- For 1,000 very active groups: 1,670 Redis writes/second
- Redis can handle 100K+ ops/second, so this is fine

**Pros:**

- Cache always up-to-date
- Fast reads (no DB query needed)

**Cons:**

- Frequent Redis writes
- Cache thrashing for very active groups
- Wasted writes if no one reads the cache

### Strategy 2: Invalidate cache on new message

**How it works:**

- When a new message arrives, delete the cache
- Next read rebuilds the cache from DB

**Math:**

- Cache invalidation: 1 Redis DELETE operation per message
- For 1,000 active groups: 1,670 Redis deletes/second
- Still within Redis limits

**Pros:**

- Simple implementation
- No cache consistency issues
- Less memory (no stale data)

**Cons:**

- Cache misses after invalidation
- Next read hits DB (but then cache is warm again)
- Thrashing for very active groups

### Strategy 3: Don't cache very active groups (recommended)

**How it works:**

- Cache only inactive/less active groups
- Very active groups: skip cache, read from DB
- Use activity threshold (e.g., >10 messages/minute = don't cache)

**Math:**

- Assume 10% of groups are very active (1M groups)
- Cache 90% of groups: 900K groups × 50 messages × 200 bytes = ~9 GB
- Very active groups: Read from DB (they're already in memory via WebSocket anyway)

**Pros:**

- Best of both worlds
- Reduces cache thrashing
- Active groups don't need cache (users already see messages via WebSocket)

**Cons:**

- Need to track group activity
- Slightly more complex logic

### Strategy 4: Lazy cache update (time-based)

**How it works:**

- Update cache only if it's older than X seconds (e.g., 30 seconds)
- Or update cache in background, not synchronously

**Math:**

- Update frequency: Max once per 30 seconds per group
- For 1,000 active groups: 1,000 ÷ 30 = ~33 updates/second
- Very manageable

**Pros:**

- Reduces cache write frequency
- Still keeps cache relatively fresh
- Good for moderately active groups

**Cons:**

- Cache can be slightly stale
- Need background job or async updates

### Recommended solution: hybrid approach

### Implementation strategy

```java
@Service
public class MessageCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private MessageRepository messageRepository;

    private static final String CACHE_KEY_PREFIX = "group:";
    private static final String CACHE_SUFFIX = ":messages:recent";
    private static final int CACHE_SIZE = 50;
    private static final int CACHE_TTL_HOURS = 1;

    // Activity threshold: groups with >10 messages/minute are considered "very active"
    private static final int VERY_ACTIVE_THRESHOLD = 10;

    /**
     * Get recent messages for a group.
     * Strategy:
     * 1. If group is very active → Skip cache, read from DB
     * 2. If group is inactive → Use cache, or read from DB if cache miss
     */
    public List<MessageResponse> getRecentMessages(Long groupId) {
        // Check if group is very active
        if (isVeryActiveGroup(groupId)) {
            // Skip cache for very active groups
            return fetchFromDatabase(groupId);
        }

        // Try cache first
        String cacheKey = CACHE_KEY_PREFIX + groupId + CACHE_SUFFIX;
        List<MessageResponse> cached = (List<MessageResponse>) redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            return cached;
        }

        // Cache miss: fetch from DB and cache it
        List<MessageResponse> messages = fetchFromDatabase(groupId);
        cacheMessages(groupId, messages);
        return messages;
    }

    /**
     * Handle new message.
     * Strategy:
     * 1. Very active groups → Invalidate cache (don't update)
     * 2. Inactive groups → Append to cache (or invalidate if cache is old)
     */
    public void onNewMessage(Long groupId, MessageResponse newMessage) {
        String cacheKey = CACHE_KEY_PREFIX + groupId + CACHE_SUFFIX;

        if (isVeryActiveGroup(groupId)) {
            // Very active groups: just invalidate cache
            // Next read will fetch from DB (which is fine, they're already seeing messages via WebSocket)
            redisTemplate.delete(cacheKey);
            return;
        }

        // Inactive groups: append to cache
        List<MessageResponse> cached = (List<MessageResponse>) redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            // Append new message to front
            cached.add(0, newMessage);

            // Keep only last CACHE_SIZE messages
            if (cached.size() > CACHE_SIZE) {
                cached = cached.subList(0, CACHE_SIZE);
            }

            // Update cache
            redisTemplate.opsForValue().set(cacheKey, cached, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } else {
            // Cache doesn't exist, invalidate (let next read rebuild)
            // Or you could create cache with just this message
            redisTemplate.delete(cacheKey);
        }
    }

    /**
     * Check if group is very active (e.g., >10 messages/minute).
     * You can track this in Redis with a counter.
     */
    private boolean isVeryActiveGroup(Long groupId) {
        String activityKey = CACHE_KEY_PREFIX + groupId + ":activity";
        Integer count = (Integer) redisTemplate.opsForValue().get(activityKey);

        // Reset counter every minute
        if (count == null) {
            redisTemplate.opsForValue().set(activityKey, 1, 1, TimeUnit.MINUTES);
            return false;
        }

        // Increment counter
        redisTemplate.opsForValue().increment(activityKey);

        return count >= VERY_ACTIVE_THRESHOLD;
    }

    private List<MessageResponse> fetchFromDatabase(Long groupId) {
        // Fetch last 50 messages from DB
        List<Message> messages = messageRepository.findTop50ByGroupIdOrderByTimestampDesc(groupId);
        return messages.stream()
                .map(MessageResponse::fromMessage)
                .collect(Collectors.toList());
    }

    private void cacheMessages(Long groupId, List<MessageResponse> messages) {
        String cacheKey = CACHE_KEY_PREFIX + groupId + CACHE_SUFFIX;
        redisTemplate.opsForValue().set(cacheKey, messages, CACHE_TTL_HOURS, TimeUnit.HOURS);
    }
}
```

### Update WebSocketController

```java
@MessageMapping("/group.send")
public MessageResponse sendGroupMessage(...) {
    // ... existing code ...

    Message savedMessage = messageRepository.save(message);
    MessageResponse response = MessageResponse.fromMessage(savedMessage);

    // Update cache (handles very active groups intelligently)
    messageCacheService.onNewMessage(group.getId(), response);

    // Send to WebSocket subscribers
    messagingTemplate.convertAndSend(destination, response);

    return response;
}
```

## Performance analysis

### Cache update frequency

**Scenario: 1,000 very active groups**

- Messages: 100 messages/minute per group = 100K messages/minute total
- Cache operations: 100K invalidations/minute = ~1,667 ops/second
- Redis capacity: 100K+ ops/second → well within limits

**Scenario: 10,000 moderately active groups**

- Messages: 10 messages/minute per group = 100K messages/minute total
- Cache operations: 100K updates/minute = ~1,667 ops/second
- Still within Redis limits

**Scenario: 1M inactive groups**

- Messages: 1 message/hour per group = 1M messages/hour = ~278 messages/second
- Cache operations: 278 updates/second
- Very manageable

### Total Redis load

| Operation           | Frequency          | Redis Ops/Second             |
| ------------------- | ------------------ | ---------------------------- |
| Cache reads         | User opens group   | ~167 reads/second (peak)     |
| Cache updates       | New messages       | ~1,667 updates/second (peak) |
| Cache invalidations | Very active groups | ~1,667 deletes/second (peak) |
| **Total**           |                    | **~3,500 ops/second**        |

Redis can easily handle this (100K+ ops/second capacity).

## Final recommendation

Use the hybrid approach:

1. Very active groups (>10 messages/minute): invalidate cache, read from DB
   - Users already see messages via WebSocket
   - Cache thrashing avoided
2. Inactive groups: append to cache
   - Fast reads
   - Minimal cache updates
3. Cache TTL: 1 hour (auto-expire stale caches)

Why this works

- Very active groups: users are already connected via WebSocket, so they see messages in real time. Cache is mainly for users who open the group later.
- Inactive groups: cache is valuable because users might open the group after hours/days, and cache avoids DB queries.

This balances performance and efficiency.

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
