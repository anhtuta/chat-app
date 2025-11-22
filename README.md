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

## Run Multiple Instances

Build the Application. This creates: target/chat-app-0.0.1-SNAPSHOT.jar

```sh
mvn clean package
```

Terminal 1 - Instance 1:

```bash
java -jar target/chat-app-0.0.1-SNAPSHOT.jar \
  --server.port=9010 \
  --spring.application.instance-id=instance-1
```

Terminal 2 - Instance 2:

```bash
java -jar target/chat-app-0.0.1-SNAPSHOT.jar \
  --server.port=9011 \
  --spring.application.instance-id=instance-2
```

Terminal 3 - Instance 3 (optional):

```bash
java -jar target/chat-app-0.0.1-SNAPSHOT.jar \
  --server.port=9012 \
  --spring.application.instance-id=instance-3
```

## ~~Explaining `#{@publicTopicQueue}`~~

It's Spring Expression Language (SpEL) used to reference a Spring bean.

- `#{}` — SpEL expression delimiter
- `@` — bean reference operator in SpEL
- `publicTopicQueue` — bean name (from the `@Bean` method in [`RabbitMQConfig`](./src/main/java/com/hello/chatapp/config/RabbitMQConfig.java))

Sao lại dùng nó?

- Method `publicTopicQueue` sẽ return dynamic queue name, có thể là `ws.instance-1.public`, `ws.instance-123.public`, tuỳ theo giá trị của instance mỗi khi run app
- Bên RabbitMQ listener, mỗi 1 instance khi run sẽ lắng nghe 1 queue riêng biệt, e.g. `ws.instance-1.public`, `ws.instance-123.public`
- Ta không thể hardcode `@RabbitListener(queues = "ws.instance-1.public")` như này được, vì mỗi 1 instance sẽ có instanceId riêng.
- Ta có thể dùng dynamic bean name: `@RabbitListener(queues = "#{@publicTopicQueue}")`

Update: Cái này đã bị xoá bỏ, vì không dùng queue `ws.instance-id.public` nữa. Thay vào đó ta dùng queue `ws.instance-id.session-id.public`

- `ws.instance-id.public`: chỉ dynamic với `instance-id`, sau khi instance start thì KHÔNG thay đổi nữa
- `ws.instance-id.session-id.public`: dynamic với `instance-id` và websocket session của user, mỗi khi user connect/disconnect 1 websocket thì 1 queue sẽ được tạo/xoá

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

## Hybrid broker using RabbitMQ: Queue per instance, WebSocket per user

Stack: Single Spring Boot app (ChatAppApplication) using MVC + STOMP-over-WebSocket

Concepts:

- Các instance sẽ dùng chung exchange. Mỗi 1 destination sẽ có 1 exchange kiểu Fanout
- Mỗi 1 instance sẽ chỉ có 1 queue và 1 listener cho 1 destination.
- Queue chỉ liên quan đến instance, còn user chỉ liên quan đến WebSocket connection.
  - Do đó chỉ cần tạo queue khi user đầu tiên đến subscribe (gửi lệnh `StompCommand.SUBSCRIBE`) đến 1 destination nào đó
  - Các destination khác cũng vậy, user nào đến subscribe đầu tiên sẽ tạo queue tương ứng
- Việc tạo exchange/queue sẽ chỉ được thực hiện khi user đầu tiên connect đến server và join group. Các user khác đến sau sẽ không tạo mới nữa

Flow đơn giản khi dùng RabbitMQ:

- Giả sử có 3 instance: 1,2,3. Chỉ có 1 exchange1 và 3 queue 1,2,3 cho mỗi instance
- Khi user1 gửi 1 message tới instance1, nó sẽ push vào exchange1 RabbitMQ
- exchange1 sẽ forward message tới cả 3 queue1, queue2, queue3
- Các listener1, listener2, listener3 nhận được message
- Các listener lại forward tới in-memory broker, trừ listener1 sẽ KHÔNG forward tới broker, vì listener1 check được message đó được gửi gửi instance1, nên nó sẽ skip
- In-memory broker của instance2 và instance3 forward message tới các WebSocket connection (tới từng user)

Luôn phải send message tới 2 chỗ:

- `messagingTemplate.convertAndSend(destination, response)`: gửi tới in-memory broker: các user khác kết nối tới cùng instance của sender sẽ nhận được message luôn
- `rabbitMQBrokerHandler.publishToRabbitMQ(destination, response)`: gửi tới RabbitMQ cho các user kết nối tới instance khác. Message sẽ phải đi qua RabbitMQ nên sẽ chậm hơn xíu

Phía Rabbit Lister sẽ phải gửi message tới in-memory broker để nó forward tới các user qua WebSocket:

- `messagingTemplate.convertAndSend(destination, response)`

Túm lại:

- User gửi message đến server (instance1)
- Nhảy vào controller của Spring boot
- Controller gửi message đến in-memory broker và RabbitMQ
- In-memory broker sẽ forward message tới các user đang connect tới instance1
- RabbitMQ sẽ gửi message tới exchange --> queue --> listener của các instance khác
- Listener sẽ gửi message đến in-memory broker
- In-memory broker sẽ forward message tới các user đang connect tới instance đó

## TODO

Fix issue:

- Running 2 instance
- Open 2 tab in the same browser, each tab accesses each instance
- Login one tab, then the other will be logged out. Why?
