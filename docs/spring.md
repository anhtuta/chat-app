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
