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
