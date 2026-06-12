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

# How does it work under the hood?

(Explain current implementation in [10_RABBITMQ_TOPOLOGY_OPTIMIZATION.md](./10_RABBITMQ_TOPOLOGY_OPTIMIZATION.md))

**RabbitMQ and Spring SimpleBroker do two different kinds of routing**.

## Step 1: RabbitMQ (instance-level)

When User1 on Instance 1 sends to group 42:

```text
publish → exchange: chat.groups
          routing key: group.42
```

RabbitMQ checks bindings on `ws.instance-2.inbound`:

| Binding on `ws.instance-2.inbound`  | Matches `group.42`? |
| ----------------------------------- | ------------------- |
| `group.42` (from User2’s subscribe) | ✓ Yes               |
| `group.45` (from User3’s subscribe) | ✗ No                |

So **one copy** of the message is put into `ws.instance-2.inbound` — not because User3 is there, but because **someone on Instance 2 subscribed to group 42** (User2), which created the `group.42` binding.

Important: RabbitMQ does **not** know User2 vs User3. It only knows:

- exchange
- routing key
- queue bindings

## Step 2: Instance 2 listener (still instance-level)

`DynamicRabbitMQListener` on Instance 2:

1. Reads message from `ws.instance-2.inbound`
2. Reads header `stomp-destination` → `/topic/group.42`
3. Calls:

```java
messagingTemplate.convertAndSend("/topic/group.42", payload);
```

So far, everything is **per instance**, not per user.

## Step 3: SimpleBroker (user-level) — this is why User2 gets it and User3 doesn’t

Spring’s in-memory broker keeps a **subscription registry per STOMP destination**:

```text
/topic/group.42  →  [User2's WebSocket session]
/topic/group.45  →  [User3's WebSocket session]
```

`convertAndSend("/topic/group.42", ...)` only delivers to clients subscribed to **`/topic/group.42`**.

- User2 subscribed to `/topic/group.42` → receives ✓
- User3 subscribed to `/topic/group.45` only → not on that topic → does not receive ✗

## Full picture in one line

```text
User1 (inst-1)
  → RabbitMQ (routing key group.42)
  → ws.instance-2.inbound        ← instance routing
  → Listener2
  → SimpleBroker /topic/group.42 ← user/topic routing
  → User2 only
```

## Common misconception

> “The message arrives in `ws.instance-2.inbound` with routing key group.42, so everyone on Instance 2 should get it.”

Not quite.

- The **queue** is shared by the whole instance.
- The **routing key** only decides whether RabbitMQ puts the message into that queue.
- **Who actually receives it** is decided later by SimpleBroker, based on each user’s STOMP subscription destination.

## Extra example

If on Instance 2 **only User3** existed and **only** subscribed to group 45:

- binding `group.45` exists
- binding `group.42` does **not** exist
- a group 42 publish would **not** even reach `ws.instance-2.inbound`

So User3 still wouldn’t get group 42 messages — **at the RabbitMQ layer, not just SimpleBroker**.

## **Exchanges, queues, and bindings are for instances**, not for individual users.

User-level delivery happens **after** RabbitMQ, inside Spring’s **SimpleBroker**, using each user’s STOMP subscription (`/topic/group.42`, `/topic/group.45`, etc.).

## What RabbitMQ knows vs what Spring knows

| Layer                                   | Scope            | What it decides                                                  |
| --------------------------------------- | ---------------- | ---------------------------------------------------------------- |
| **RabbitMQ** (exchange, queue, binding) | **Instance**     | “Should **Instance 2** receive messages for **group 42**?”       |
| **SimpleBroker** (STOMP subscribe)      | **User/session** | “Which **users on Instance 2** subscribed to `/topic/group.42`?” |

So in your example:

1. RabbitMQ delivers **one message** to `ws.instance-2.inbound` (because Instance 2 has a `group.42` binding).
2. Instance 2 listener forwards to SimpleBroker on `/topic/group.42`.
3. SimpleBroker sends only to **User2** (subscribed to group 42), not **User3** (subscribed to group 45).

## Why not one queue + one direct exchange + no routing keys?

You _could_ do something like this:

```text
1 exchange (fanout or direct)
1 queue per instance
1 binding (always on)
→ every instance receives every group message
→ listener filters in app memory: "do we have local subscribers?"
```

That is basically **Solution 2** in your [doc (single fanout envelope)](./10_RABBITMQ_TOPOLOGY_OPTIMIZATION.md#2-single-fanout-envelope-exchange-per-traffic-class). It works, but has a big cost at scale.

### Problem: every instance gets every message

Example: 20 backend instances, message sent to **group 42**.

| Approach                                | Who receives from RabbitMQ?                                   |
| --------------------------------------- | ------------------------------------------------------------- |
| **Single exchange, no per-group keys**  | All 20 instances                                              |
| **Topic exchange + `group.42` binding** | Only instances with at least one local subscriber to group 42 |

With the simple approach, Instance 2 would receive group 42 messages even if **only User3 (group 45)** is connected there. The app would deserialize JSON, check memory, then throw it away.

That waste grows as:

```text
total cross-instance traffic ≈ messages × number of instances
```

instead of:

```text
total cross-instance traffic ≈ messages × instances that actually care
```

## Why topic exchange + routing keys per group?

Because you want **both**:

1. **Fixed topology** (few exchanges, one queue per instance) — Phase 1+2 goal
2. **Targeted cross-instance delivery** — don’t spam instances with no relevant subscribers

Topic exchange gives you:

```text
chat.groups
  ├─ binding group.42 → ws.instance-2.inbound   (User2 subscribed)
  ├─ binding group.45 → ws.instance-2.inbound   (User3 subscribed)
  └─ (no binding)     → ws.instance-3.inbound   (nobody on inst-3 cares about 42 or 45)
```

When User1 publishes to group 42:

- routing key = `group.42`
- RabbitMQ matches only queues with a **`group.42` binding**
- Instance 3 gets **nothing**
- Instance 2 gets the message (because User2 created that binding)

So routing keys are **not for users** — they are for **which group topics this instance is interested in**.

## Why not Direct exchange with one key?

A Direct exchange with a **single** routing key behaves like “everyone bound gets everything” (same waste as fanout envelope).

A Direct exchange with **different** keys per group is almost the same idea as Topic here — you still need per-group keys. Topic is a natural fit because keys like `group.42`, `group.99` are hierarchical/prefix-friendly and you already use one exchange for all groups.

## Mental model

Think of two filters in series:

```text
Filter 1 (RabbitMQ):  "Which INSTANCES need this group message?"
                      → topic exchange + bindings on instance queue

Filter 2 (SimpleBroker): "Which USERS on that instance subscribed to this topic?"
                      → STOMP /topic/group.42 subscriptions
```

User3 is blocked at **Filter 2**.  
An instance with no group-42 subscribers is blocked at **Filter 1** — and that’s why per-group routing keys matter.

## Short answer

- **Yes**, exchanges/queues/bindings are **instance-level**.
- **No**, we don’t use one queue + one exchange with no keys because that would **broadcast every group message to every instance**, then filter in Java.
- **Topic exchange + per-group routing keys** lets RabbitMQ do the first filter cheaply: only instances with local subscribers to that group receive the message.

If you only ever run **2–3 instances**, the simple single-binding approach might be fine. At **many instances + many groups**, the topic + dynamic binding model is why Phase 1+2 was chosen.
