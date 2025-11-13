# Chat application

A simple chat application using Spring Boot + WebSocket

Ref:

- https://www.callicoder.com/spring-boot-websocket-chat-example/
- Cursor AI

I will mainly use Cursor editor to help me to code, my main responsibilities are:

- Prompt
- Review code

## How Spring converts JSON to Message

Here is a method in a controller:

```java
@MessageMapping("/chat.send")
@SendTo("/topic/public")
@NonNull
public Message sendMessage(@Payload @NonNull Message message) {
    // Save message to database
    return messageRepository.save(message);
}
```

Explain annotations:

- `@MessageMapping("/chat.send")`: Maps WebSocket messages sent to `/app/chat.send` to this method. The `/app` prefix comes from the [WebSocket configuration](./src/main/java/com/hello/chatapp/config/WebSocketConfig.java)
- `@SendTo("/topic/public")`: Broadcasts the return value to all subscribers of `/topic/public`. All connected clients receive the message
- `@Payload @NonNull Message message`
  - `@Payload`: extracts the message body from the WebSocket frame, then convert it from JSON to the specified type (`Message`), then inject it as the method parameter
  - `@NonNull`: ensures the parameter is not null

When a message arrives at `@MessageMapping("/chat.send")`, Spring:

1. Extracts the JSON payload (via `@Payload`)
2. Uses `Jackson` to deserialize the JSON string into a `Message` object
3. Matches JSON fields to `Message` fields (sender, content, timestamp)

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

**Result:**

- Simple authentication validation
- Prevents message spoofing
- No complex session tracking
- Minimal performance overhead (just a HashMap lookup)

The solution now only validates that WebSocket messages come from authenticated users, without tracking cookie deletion.

## Handles join notifications in BE

1. **Backend now handles join notifications** (`WebSocketEventListener`):

   - Updated `handleWebSocketConnectListener` to automatically send a join notification when a user connects
   - Uses the same pattern as disconnect notifications for consistency

2. **Removed frontend join notification** (`index.html`):

   - Removed the manual `stompClient.send("/app/chat.addUser", ...)` call
   - Added a comment explaining that the backend handles it automatically

3. **Cleaned up unused code** (`WebSocketController`):
   - Removed the `addUser` method since it's no longer needed

## Benefits

- Consistent behavior: both join and disconnect are handled by the backend
- More secure: join notifications can't be faked by clients
- Cleaner frontend: less code to maintain
- Automatic: notifications happen when the WebSocket connection is established

When a user connects, the backend automatically broadcasts "{username} joined the chat" to all clients, just like it does when they disconnect.

## How the Message Broker Handles Group Messages

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
   // WebSocketController.sendGroupMessage()
   // - Validates user
   // - Fetches group from database
   // - Saves message to database
   // - Sends to topic: "/topic/group.1"
   // Controller chỉ save message vào DB, còn broadcast nó cho user khác là việc của broker.
   // Do đó controller sẽ gửi message tới broker để nó forward message tới người nhận.
   // Với lệnh sau, controller sẽ gửi message tới broker (nếu dùng in-memory broker thì nó chính là STOMP broker đó)
   messagingTemplate.convertAndSend("/topic/group.1", response);
   ```

3. Broker routes the message:

   - The broker receives a message with destination `/topic/group.1`
   - It looks up all subscribers to `/topic/group.1`
   - It forwards the message to all subscribed clients

4. Only subscribed clients receive it:
   - Only clients subscribed to `/topic/group.1` receive the message
   - Clients subscribed to `/topic/group.2` do not receive it
   - Clients only subscribed to `/topic/public` do not receive it

### 4. How the broker knows which users to forward to

The broker does not know about users or groups. It only knows:

- Topic destinations (e.g., `/topic/group.1`)
- Which WebSocket sessions are subscribed to each topic

The broker forwards messages to all subscribers of a topic. It does not:

- Check if a user is a member of the group
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

## STOMP protocol

STOMP is a simple text-oriented messaging protocol used by our UI Client (browser) to connect to enterprise message brokers.

Clients can use the `SEND` or `SUBSCRIBE` commands to **send or subscribe for messages** along with a **"destination" header** that describes what the message is about and who should receive it.

It defines **a protocol for clients and servers to communicate with messaging semantics**. It does not define any implementation details, but rather addresses an easy-to-implement wire protocol for messaging integrations.

The protocol is **similar to HTTP**, and **works over TCP using the following commands**:

```
CONNECT
SEND
SUBSCRIBE
UNSUBSCRIBE
BEGIN
COMMIT
ABORT
ACK
NACK
DISCONNECT
```

When using **Spring's STOMP support**, the Spring WebSocket application acts as the **STOMP broker** to clients. Messages are routed to `@Controller` message-handling methods or to a simple, in-memory broker that keeps track of subscriptions and broadcasts messages to subscribed users.

You can also configure Spring to work with a dedicated STOMP broker (e.g. RabbitMQ, ActiveMQ, etc.) for the actual broadcasting of messages. In that case, Spring maintains TCP connections to the broker, relays messages to it, and also passes messages from it down to connected WebSocket clients.

Ref: https://dzone.com/articles/build-a-chat-application-using-spring-boot-websock
