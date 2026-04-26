## Why we typically don't delete exchanges on shutdown

1. Multi-instance safety: In a multi-instance setup, one instance shutting down shouldn't delete exchanges that other instances may still use.
2. Lightweight: Exchanges are metadata and consume minimal resources.
3. Idempotent creation: `declareExchange` is idempotent, so recreating on startup is safe.
4. Persistence: Durable exchanges persist across broker restarts, which is often desired.

### Recommendation

**For production: don't delete exchanges** (current approach is fine).

**For development/testing:** If you want cleanup, you can track exchanges, but be cautious in multi-instance setups.

Keep the current approach (no exchange cleanup):

1. Exchanges persist and are reused
2. Safe for multi-instance
3. **Manual cleanup via RabbitMQ Management UI when needed**
4. Exchanges are lightweight and don't cause issues

## WebSocket Authentication

**How it works:**

1. **During WebSocket handshake** (`WebSocketHandshakeInterceptor`):
   - Extracts username from the HTTP session (set during login)
   - Stores it in WebSocket session attributes

2. **On each WebSocket message** (`WebSocketSecurityChannelInterceptor`):
   - Validates that username exists in WebSocket session attributes
   - Rejects message if not authenticated

3. **In message handlers** (`WebSocketController`):
   - Uses the authenticated username from WebSocket session
   - Prevents spoofing (client can't fake the sender)

## How the In-Memory Message Broker Handles Group Messages

### 1. Topic-based routing

Spring's simple in-memory broker uses topic-based routing. Topics are string destinations like:

- `/topic/public` - for public chat messages
- `/topic/group.1` - for group 1 messages
- `/topic/group.2` - for group 2 messages
- etc.

### 2. Subscription model

When a client connects and wants to receive messages from a group:

```javascript
// Frontend subscribes to a specific group topic
stompClient.subscribe(`/topic/group.${chatId}`, function (message) {
  showMessage(JSON.parse(message.body));
});
```

The broker maintains an internal subscription map:

```
Subscription Registry:
├── /topic/public
│   ├── Client A (WebSocket session)
│   ├── Client B (WebSocket session)
│   └── Client C (WebSocket session)
├── /topic/group.1
│   ├── Client A (member of group 1)
│   └── Client D (member of group 1)
└── /topic/group.2
    ├── Client B (member of group 2)
    └── Client E (member of group 2)
```

### 3. Message flow when a user sends to group1

Step-by-step:

1. User (FE) sends message:

   ```javascript
   // Frontend sends to /app/group.send
   chatMessage.groupId = 1;
   stompClient.send("/app/group.send", {}, JSON.stringify(chatMessage));
   ```

   - Note: FE send message, không hiển thị message đó luôn, mà phải chờ message được gửi tới BE, rồi BE gửi lại message đó thì FE mới hiển thị

2. Backend receives and processes:

   ```java
   // Controller chỉ save message vào DB, còn broadcast nó cho user khác là việc của broker.
   // Do đó controller sẽ gửi message tới broker để nó forward message tới người nhận.
   // Với lệnh sau, controller sẽ gửi message tới broker (nếu dùng in-memory broker thì nó chính là STOMP broker đó)
   messagingTemplate.convertAndSend("/topic/group.1", response);
   ```

3. Broker routes the message:
   - The broker receives a message with destination `/topic/group.1`
   - It looks up all subscribers to `/topic/group.1`
   - It forwards the message to all subscribed clients

### 4. How the broker knows which users to forward to

The broker does not know about users or groups. It only knows:

- Topic destinations (e.g., `/topic/group.1`)
- Which WebSocket sessions are subscribed to each topic

The broker forwards messages to all subscribers of a topic. It does NOT:

- Check if a user is a member of the group: do đó khi 1 user subscribe 1 destination, ta phải check xem nó có là member của group đó không: [WebSocketSecurityChannelInterceptor.java::validateSubscription()](./src/main/java/com/hello/chatapp/config/WebSocketSecurityChannelInterceptor.java)
- Query the database
- Know about user relationships

### 5. Important points

1. Subscription happens on the client side:
   - When a user opens a group chat, the frontend subscribes to that group's topic
   - The backend does not automatically subscribe users

2. Security consideration:
   - The broker forwards to all subscribers of a topic
   - **Without authorization, any user could subscribe to any group topic and receive messages**
   - Therefore, authorization must be enforced at multiple levels:
     - When subscribing to topics (prevents unauthorized subscription)
     - When loading messages (prevents unauthorized message retrieval)
     - When sending messages (prevents unauthorized message sending)

3. Current implementation:
   - ✅ Authorization when subscribing: `WebSocketSecurityChannelInterceptor.validateSubscription()` prevents unauthorized subscriptions
   - ✅ Authorization when loading messages: `MessageController.getGroupMessages()` checks membership
   - ✅ Authorization when sending messages: `WebSocketController.sendGroupMessage()` verifies membership before sending

### 6. Visual flow diagram

```
User A (member of group 1) sends message:
┌─────────────┐
│  Frontend   │ → sends to /app/group.send (groupId=1)
└─────────────┘
       ↓
┌─────────────────────┐
│ WebSocketController │ → validates, saves to DB
└─────────────────────┘
       ↓
┌─────────────────────┐
│ messagingTemplate   │ → convertAndSend("/topic/group.1", message)
└─────────────────────┘
       ↓
┌─────────────────────┐
│  Message Broker     │ → looks up subscribers of "/topic/group.1"
│  (SimpleBroker)     │
└─────────────────────┘
           ↓
    ┌──────┴──────┐
    ↓             ↓
┌─────────┐  ┌─────────┐
│Client A │  │Client D │  (both subscribed to /topic/group.1)
└─────────┘  └─────────┘
```
