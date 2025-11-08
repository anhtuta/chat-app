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
