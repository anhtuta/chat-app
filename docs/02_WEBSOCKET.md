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

# Can we only have one type of subscription?

What happens if we only have one kind of subscription: `/topic/group.id`, and remove the subscription `/topic/user.id.group-updates`. Which means: suppose we have:

- 1,000 online users
- 1,000 groups per user
- 1,000 members per group

Do the math:

- Total WebSocket Connections: 1,000
- Total STOMP Subscriptions: 1,000,000 (1 Million).

I guess STOMP subscription is quite Lightweight, so the server can handle 1M subs well.

Given this solution, how do u think it's possible? each user now just care about destinations for each group they have, no need group-updates anymore, which make it more simpler. We can remove the fan-out issue. But we have to refactor a lot in our code.

Answer from GPT 5.4:

Yes, this is **architecturally possible**, and it **does remove the bad backend fan-out loop**. But there is an important distinction:

- You are removing **publisher-side fan-out in Java**.
- You are **not** removing **delivery fan-out overall**. You are moving it into the STOMP/WebSocket layer.

If that is acceptable, the design can be much simpler.

## What gets better

With only per-group subscriptions, the backend no longer does this:

```java
for each member in group:
  convertAndSend("/topic/user.{username}.group-updates", ...)
```

Instead it becomes:

```java
convertAndSend("/topic/group.{groupId}", event)
```

That means:

- no member lookup on each message
- no per-member Java loop
- no per-member RabbitMQ publish
- much simpler mental model: "group event goes to group topic"

That part is very good.

## The main thing to be careful about

Your math for subscriptions is fine:

- `1,000 users`
- `1,000 groups per user`
- `=> 1,000,000 subscriptions`

That **might** be okay, but **I would not blindly assume Spring's in-memory STOMP broker will handle 1M subs comfortably without load testing**. Subscriptions are much lighter than connections, yes, but 1M `session x destination` entries is still real memory and lookup cost.

More importantly, **subscriptions are probably not the biggest cost**.

The bigger cost is **message delivery volume**.

With your numbers:

- total memberships = `1,000 * 1,000 = 1,000,000`
- if each group has `1,000 members`
- then total distinct groups is about `1,000`

Now suppose each group gets `1 message/second`.

That means:

- `1,000 incoming group messages/second`
- each message must reach `1,000 members`
- total deliveries = **1,000,000 message deliveries/second**

So the real question is not "can we store 1M subscriptions?".  
It is "**do we want every online user to receive the live message stream for all 1,000 groups** they belong to?"

That is where this design can get expensive.

## The critical product question

There are two versions of your idea.

### Version A: use `/topic/group.{id}` for actual chat messages, and subscribe to all groups

Then the sidebar can derive its last message / unread / preview directly from the real message stream. In that case, `group-updates` disappears entirely.

This is the simplest model.

But the cost is:

- every user receives full chat traffic for every group they belong to
- network usage grows a lot
- client CPU / memory grows a lot
- mobile/browser battery cost grows
- users not viewing a group still receive all its live message payloads

This is the part I would worry about most.

### Version B: use per-group subscriptions, but for lightweight summary events

Example:

- `/topic/group.{id}.summary` for sidebar updates for all joined groups
- `/topic/group.{id}` or `/topic/group.{id}.messages` only for the currently opened chat

This still removes the backend fan-out issue, but avoids flooding every client with every full message body from all groups.

From a scaling perspective, I think **Version B is much safer**.

Tóm lại thì:

- Vẫn nên dùng 1 subscription cho mỗi user
- 1 subscription cho group đang mở
- 1 subscription cho summary của các group còn lại

## My opinion on your proposal

I like the direction, but I would refine it:

**Good idea:** remove `/topic/user.{id}.group-updates` and stop doing per-member fan-out.  
**Risky part:** making every user subscribe to the full chat stream of every group.

So I would recommend:

- keep the **per-group model**
- but not necessarily a single literal destination for everything
- use a **per-group summary stream** for the sidebar
- use the **chat stream** only for active/opened groups

That gives you the same structural win:

- one publish per group event
- no member loop
- no per-user topic
- simpler RabbitMQ routing
- cleaner multi-instance behavior

## RabbitMQ impact with your model

This part actually becomes nicer.

If you move to per-group topics, RabbitMQ can route by `group.{id}` or `group.{id}.summary`.

Then per update:

- sender instance publishes once
- each instance with local subscribers receives once
- local broker fans out to sessions

So cross-instance cost becomes roughly:

- **O(instances with interested users)**

instead of:

- **O(members)**

That is a strong improvement.

Also, note that RabbitMQ bindings are per **destination per instance**, not per user, if implemented well. So even if there are 1M client subscriptions, RabbitMQ may only need bindings for the distinct group destinations that actually exist on that instance.

## Refactor cost

You’re right that this is a meaningful refactor. It touches:

- frontend subscription lifecycle
- unread/last-message derivation
- WebSocket security validation
- RabbitMQ routing keys / binding names
- deprecating `/topic/user.{username}.group-updates`
- possibly separating "chat message" vs "sidebar summary" event types

So I’d call it a **medium-to-large refactor**, but the architecture is cleaner afterward.

## Bottom line

- **Possible?** Yes.
- **Simpler than current per-user update topic?** Yes.
- **Removes backend fan-out issue?** Yes.
- **Would I use exactly one `/topic/group.{id}` subscription for all groups and all message traffic?** Probably **NO**, unless message rate is known to be low.

My preferred version is:

- remove `/topic/user.{id}.group-updates`
- move to **per-group subscriptions**
- but keep **summary events** separate from **full chat message events**

That gives you the simplicity you want without making every client consume the full live traffic of all joined groups.

If you want, I can next rewrite `docs/11_GROUP_SUMMARY_UPDATE_FANOUT_SCALING.md` around this newer direction and compare these 3 options clearly:

1. current per-user summary topic
2. per-group summary topic
3. one per-group event stream for everything

# Memory impact of 1 million subscriptions

By: Gemini 3.5 Flash, Extended

Spring's in-memory registry requires approximately **200 MB to 250 MB** of Heap RAM.

### 1. Breakdown of the JVM Heap Math

Spring Boot manages these entries using internal metadata classes (like `Subscription` and `SessionInfo`) tucked inside a nested `ConcurrentHashMap`.

| Object Type                                                                                 | Size per Instance | Quantity  | Total Memory Impact  |
| ------------------------------------------------------------------------------------------- | ----------------- | --------- | -------------------- |
| **`Subscription` Objects** <br>_(Header + ID ref + Destination ref)_                        | ~24 bytes         | 1,000,000 | **~24 MB**           |
| **Subscription ID Strings** <br>_(Compact Strings like "sub-0" to "sub-999")_               | ~56 bytes         | 1,000,000 | **~56 MB**           |
| **Map Nodes (`ConcurrentHashMap.Node`)** <br>_(Internal structures linking keys to values)_ | ~32 bytes         | 1,000,000 | **~32 MB**           |
| **Map Bucket Arrays** <br>_(2048 buckets per user session map)_                             | ~8,192 bytes      | 1,000     | **~8 MB**            |
| **Spring's Destination Indexing** <br>_(Reverse lookup map for path-matching)_              | Variable          | N/A       | **~60 MB – 80 MB**   |
| **Total Estimated Footprint**                                                               |                   |           | **~180 MB – 200 MB** |

### 2. The Real Danger: Memory Beyond the Subscriptions

While the raw registry entries take up less than 250 MB, running 1 million subscriptions over 1,000 active WebSockets in production introduces two hidden memory traps:

- **The Tomcat Buffer Trap:** By default, embedded Tomcat allocates I/O read/write frame buffers per active connection. If configured poorly (e.g., a 1MB max message size buffer), 1,000 connections can instantly claim **2 GB to 3 GB** of memory purely for socket networking buffers before handling any messaging logic.

  > _Fix:_ Keep your `maxTextMessageBufferSize` and `maxBinaryMessageBufferSize` restricted to standard sizes like 8KB or 16KB.

- **The Slow Consumer Accumulation:** When a group receives a massive burst of chat traffic, Spring places those messages into an **in-memory outbound queue** for each subscriber. If 50 users are on slow or unstable mobile networks, Spring will continue buffering messages in the JVM heap waiting for those clients to read them. If left unchecked, these unconsumed message payloads can quickly consume gigabytes of RAM and trigger an Out of Memory (OOM) crash.
