# Group Summary Update Fan-Out Scaling

## Current Problem

`GroupSummaryUpdatePublisher` currently performs **per-member application fan-out** for every saved group message:

- Load all usernames in the group
- For each username:
  - `messagingTemplate.convertAndSend("/topic/user.{username}.group-updates", update)`
  - `rabbitMQBrokerHandler.publishToRabbitMQ("/topic/user.{username}.group-updates", update)` (multi-instance only)

```java
@Async("groupSummaryUpdateExecutor")
public void publishToGroupMembers(Long groupId, GroupSummaryUpdate update) {
    List<String> usernames = groupParticipantRepository.findParticipantUsernamesByGroupId(groupId);
    for (String username : usernames) {
        String safeUsername = Objects.requireNonNull(username);
        String userScopedTopicDestination = "/topic/user." + safeUsername + ".group-updates";
        messagingTemplate.convertAndSend(userScopedTopicDestination, Objects.requireNonNull((Object) update));
        rabbitMQBrokerHandler.publishToRabbitMQ(userScopedTopicDestination, update);
    }
}
```

This means the backend does **O(group_members)** publish calls per saved group message.

Example for a busy group:

- Group size: **1,000 members**
- Message rate: **10 messages/second**

Current cost becomes approximately:

- **10,000 RabbitMQ publishes/second** (multi-instance)
- **10,000 `convertAndSend` calls/second** (application loop)
- **10 username-list DB queries/second**

Important nuance:

- Phase 1+2 in `10_RABBITMQ_TOPOLOGY_OPTIMIZATION.md` fixed **RabbitMQ exchange / queue explosion**.
- It did **not** fix **application-layer fan-out** in `GroupSummaryUpdatePublisher`.
- `@Async` only moves work off the chat-send thread; it does **not** reduce total work.

### Why we chose per-user topics originally

See `06_REALTIME_GROUP_SIDEBAR_UPDATE_STRATEGY.md`: each user keeps **one** sidebar subscription (`/topic/user.{username}.group-updates`) regardless of how many groups they belong to. That optimizes **client subscription count**, but pushes cost to the **publisher**, which loops over every member on every message.

```text
Per-user topic model:
  Client subscriptions: O(1) per user
  Backend publish calls:  O(members) per group message
```

## Possible Solutions

Solutions below are ordered from **simplest** to **largest architectural change**. Numbers are reused from the first draft where they still apply; single-instance analysis above should be read first.

### 1. Buffer summary updates per group (fixed-interval throttle)

**How it works**

- Keep the current per-user destination contract: `/topic/user.{username}.group-updates`.
- Instead of publishing immediately for every saved group message, buffer by `groupId` on a **fixed interval** (for example 200ms to 1000ms).
- During each interval, keep only the **latest** `GroupSummaryUpdate`.
- When the timer fires, publish one round of per-member updates for the latest state.
- If new messages arrive during or after a flush, schedule the **next** flush without resetting the in-flight timer (unlike debounce).

In short:

- Buffer by `groupId` on a fixed clock; keep only the latest `GroupSummaryUpdate`; flush at most once per interval while activity continues.

**Pros**

- Smallest change; works with current per-user topics or future per-group topics.
- Cuts burst traffic sharply.
- Sidebar stays fresh during sustained chat (bounded staleness ≈ one interval), not only after a pause.

**Cons**

- With **per-user loop**, still **O(members)** per flush.
- With **per-group topic**, buffering reduces WebSocket/RabbitMQ message rate but publisher was already O(1).

**Recommendation:** **Yes** as a complement, especially for busy chats. **Not sufficient alone** if still using per-member loop.

### 1b. Online-only fan-out (skip offline users)

**How it works**

- Keep the current per-user destination contract: `/topic/user.{username}.group-updates`.
- Before each flush, skip users who have **no active `group-updates` subscription anywhere in the cluster**.
- Track cluster-wide subscription presence in Redis (`ws:group-updates:count:{username}`): each instance increments on first local subscribe and decrements on last local unsubscribe (`GroupUpdatesSubscriptionRegistry`).
- Per-instance local delivery uses `CustomRabbitMQBrokerHandler.hasLocalSubscribers(destination)` so `convertAndSend` runs only when this node hosts a subscriber; RabbitMQ publish still runs for online users on other instances.
- If Redis is unavailable, fail open (`hasClusterSubscriber` returns `true`) so sidebar updates are not silently dropped.

**Pros**

- Smallest incremental change; no frontend or destination-model migration.
- Cuts wasted RabbitMQ publishes and `convertAndSend` calls when most group members are offline (typical for large groups).
- Works with Phase 1 buffering and multi-instance deployment.
- Handles multi-tab and multi-instance sessions via Redis refcount.

**Cons**

- Still **O(members)** loop per flush (DB username scan unchanged); only skips the publish step for offline users.
- Depends on Redis for accurate skip behavior (degrades to full fan-out when Redis is down).
- Offline users do not receive real-time sidebar events until they reconnect (sidebar state still correct on next `GET /api/groups`).

**Recommendation:** **Yes** — implemented as Phase 1b alongside buffering. Complements Solution 1; does not replace per-group summary topics for very large active groups.

### 2. Per-group summary topic — single publish, subscribers on each instance (recommended structural fix)

**How it works**

- **STOMP destination:** `/topic/group.{groupId}.summary`
- **RabbitMQ:** exchange `chat.groups` / routing key `group.{groupId}.summary`
- **Publish:** once per summary update (after buffering optional).
- **Subscribe:** each client subscribes to summary topics for groups they belong to; `WebSocketSecurityChannelInterceptor` validates membership.
- **Instance binding:** add `group.{id}.summary` binding when the first local user subscribes; remove when last unsubscribes.
- Each backend instance tracks whether it currently has **any connected user who is a member of that group**.
- If yes, that instance binds its inbound queue to `group.{groupId}.summary`.
- When a new group message is saved, publish **one** RabbitMQ message for the group summary.
- Only instances that currently host online members of that group receive it.
- On each receiving instance, fan out locally to the affected users.

**Pros**

- **O(1)** backend publish per instance per update (plus **O(instances)** across cluster).
- Aligns with how group **chat** already works (`/topic/group.{id}`).
- Removes DB username scan on every message.
- Fixes both single-instance and multi-instance fan-out.

**Cons**

- Frontend change: **M subscriptions** per user (M = groups in sidebar), not 1 personal stream.
- Must secure summary topics same as group chat topics.
- Unread/badge logic stays in frontend (already today).
- More complex than simple buffering.

**Recommendation for our problem:** **Yes** — best balance after single-instance analysis. Supersedes “per-user topic + per-instance local fan-out to personal destinations” (old Solution 2), which kept O(members) work on the publishing instance.

Là sao?

- Hiện tại đang dùng per-user summary topic, destination là `/topic/user.{id}.group-updates`
- Bây giờ chuyển sang per-group summary topic, destination là `/topic/group.{id}.summary`
- Ưu điểm:
  - Khi có 1 tin nhắn mới, ko cần fan-out cho từng người trong nhóm đó nữa
- Nhược điểm:
  - Frontend phải subscribe to `/topic/group.{id}.summary` cho tất cả các group của user

### 3. Per-group topic on backend only, keep fan-out to personal user topics locally (hybrid)

**How it works**

- One RabbitMQ message per group summary to each instance.
- Receiving instance still loops members and sends to `/topic/user.{username}.group-updates`.

**Pros**

- No frontend change.
- Fixes cross-instance RabbitMQ cost.

**Cons**

- **Still O(local_members)** `convertAndSend` per instance per message.
- Does not fix single-instance fan-out.

**Recommendation:** **Maybe** as a migration step only; **No** as end state for large groups.

### 4. Keep per-user topics + buffer only

**How it works**

- No destination model change; buffer the existing loop on a fixed interval per `groupId`.

**Pros**

- Minimal diff.

**Cons**

- Sustained load in large groups remains **O(members)** per flush.

**Recommendation:** **Short-term patch only** if per-group summary topics are deferred.

### 5. Batch summary updates per user

**How it works**

- Keep personal destinations such as `/topic/user.{username}.group-updates`.
- Instead of sending one summary update event per group message, aggregate multiple changed groups into one payload per user, for example:
  - `{ changedGroupIds: [42, 45, 99] }`
  - or a compact list of latest summaries
- Flush on a short timer.

**Pros**

- Still compatible with the personal-stream model.
- Better than raw per-message per-user fan-out during bursts.
- Reduces WebSocket frame count and RabbitMQ message count.

**Cons**

- Still publisher-centric fan-out if using per-user destinations.
- More payload and buffering logic.
- Requires careful deduplication and ordering rules.

**Recommendation:** **Maybe** with per-user model only; less important if Solution 2 is adopted.

### 6. Hybrid push + pull: push only invalidation, let client refresh summaries

**How it works**

- On group message, push only a lightweight signal such as:
  - `{ groupId: 42 }`
  - or `{ changedGroupIds: [...] }`
- The frontend then fetches fresh sidebar data from HTTP for the changed groups or the whole sidebar.

**Pros**

- Tiny push payload; easy to coalesce.
- Removes strict need for exact latest-summary payload in the event stream.

**Cons**

- Extra HTTP load; needs client debouncing.

**Recommendation:** **Maybe** as degraded mode or complement.

### 7. Dedicated summary worker / internal queue

**How it works**

- Decouple summary emission from chat-send path; worker debounces and publishes.

**Pros**

- Isolates summary-update load from the primary chat-send path.
- Better observability and backpressure control.
- Easier to add batching, retry, and rate limiting.

**Cons**

- More moving parts and operational complexity.
- Still needs a downstream fan-out strategy; by itself it does not solve per-member publish volume.

**Recommendation:** **Later**, if summary traffic is its own bottleneck.

### 8. CQRS / read-model for sidebar

**How it works**

- Treat the sidebar as a derived read model.
- Update unread counts / latest message preview in a dedicated store.
- Clients poll occasionally or receive coarse invalidation events instead of exact per-message per-user pushes.

**Pros**

- Best long-term scalability for very large systems.
- Decouples chat message throughput from sidebar update throughput.
- Supports caching and efficient list reads.

**Cons**

- Biggest architectural change.
- More data modeling and operational complexity.
- Overkill unless scale truly demands it.

**Recommendation:** **Future** path per `10_RABBITMQ_TOPOLOGY_OPTIMIZATION.md`.

### 9. One per-user event stream for everything, remove the subscription `/topic/user.{id}.group-updates`

Xem thêm tại [02_WEBSOCKET.md](02_WEBSOCKET.md#can-we-only-have-one-type-of-subscription)

## Two main solutions: per-user summary vs per-group summary

Your read on the trade-off is right: **both options are valid**, and the better choice depends on **group size distribution**, not just total group count.

### The trade-off is real

|                      | Option 1: per-user summary              | Option 2: per-group summary   |
| -------------------- | --------------------------------------- | ----------------------------- |
| Backend publish cost | **O(members in that group)** per update | **O(1)** per update           |
| Client subscriptions | **1** per user                          | **O(groups user belongs to)** |
| Refactor size        | small (already there)                   | medium/large                  |
| Pain point           | large, active groups                    | users in many groups          |

Neither is universally better.

### Your real-world pattern matters more than worst-case math

The scary example (`1,000 members × 1,000 groups × 1,000 users`) is useful for stress testing, but it is not how most chat apps look.

What you described is much more typical:

- many groups
- mostly **small** (2-person DMs, small friend circles)
- few huge communities

For that shape, Option 1 is usually fine because fan-out cost is:

```text
publish_cost ≈ members_in_the_group_that_just_got_a_message
```

not:

```text
number_of_groups_the_sender_belongs_to
```

So if someone sends in a 2-member group, current code does ~2 publishes. Even at 10 msg/s, that is ~20 publishes/s — trivial.

Option 1 only hurts when you get:

```text
large_group_size × message_rate
```

Example: 1,000-member group × 10 msg/s = 10,000 publishes/s. That is where Option 2 wins.

### Option 1 + buffering: yes, I think that is a good fit for your case

For mostly small groups, **Option 1 + buffering** is a sensible, pragmatic choice.

Buffering helps with:

- burst traffic in active chats (“ok”, “yeah”, “lol” in 300ms)
- sustained chat where the sidebar should refresh on a predictable cadence, not only after a pause
- reducing RabbitMQ + `convertAndSend` spikes
- keeping one simple client subscription model

It does **not** change the big-O (still O(members) per flush), but for 2–10 member groups that is usually irrelevant.

So yes — for “many small groups, few giants,” **Option 1 + buffering + online-only fan-out is a suitable solution**, and the large refactor to per-group subscriptions is probably not worth it yet.

## Recommendation

Recommendation path:

1. Phase 1: Add **buffering per `groupId`** in `GroupSummaryUpdatePublisher`.
2. Phase 2: Move to **per-group RabbitMQ summary publish + per-instance local fan-out** if metrics show large-group pressure.
3. Phase 3 (optional): Introduce a **dedicated summary worker / queue** if update volume is still high.
4. Phase 4 (optional): Revisit **CQRS / read model** if sidebar semantics outgrow event-push delivery.

## Chosen Solution + Implementation

### Phase 1 — fixed-interval buffering (implemented)

1. Keep the current personal summary topic contract: `/topic/user.{username}.group-updates`.
2. Add **fixed-interval buffering per `groupId`** inside `GroupSummaryUpdatePublisher`.
3. During each buffer interval, keep only the latest `GroupSummaryUpdate` for that group.
4. When the timer fires, run one per-member flush for the latest state.
5. If new messages arrive during or after a flush, schedule the next flush without resetting the in-flight timer.

Current buffering behavior:

- Interval: **3000ms** (fixed clock per group, not reset on every message)
- Scope: **per `groupId`**
- Payload policy: **latest update wins**
- Delivery contract: unchanged personal topic fan-out after each buffer flush

Why buffering instead of debounce:

- Sidebar is a live summary: users expect it to stay reasonably fresh during sustained chat, not only after a pause.
- Debounce resets the timer on every message, so continuous traffic can delay sidebar updates indefinitely.
- Buffering caps staleness at roughly one interval while still coalescing many messages into one flush.

### Phase 1b — online-only fan-out (implemented)

**What changed**

- `GroupSummaryUpdatePublisher.flushGroupMembers` skips users with no active `/topic/user.{username}.group-updates` subscription in the cluster before publishing.
- New `GroupUpdatesSubscriptionRegistry` stores a Redis refcount per username (`ws:group-updates:count:{username}`).
- `CustomRabbitMQBrokerHandler` updates the registry when the first/last local client subscribes or unsubscribes to a `group-updates` destination (reuses existing `destinationSubscriptionCount` transitions).
- Local `convertAndSend` is gated by `hasLocalSubscribers(destination)`; RabbitMQ publish still runs when the user is online on another instance.

**Why it changed**

- Large groups often have few online members at any moment; fanning out to every member on every flush wasted RabbitMQ publishes and broker work.
- Subscription existence is a better signal than “user exists in group” for real-time sidebar delivery.
- Why don't we use `SimpUserRegistry`? See [02_WEBSOCKET.md](02_WEBSOCKET.md#how-to-check-if-a-user-is-online-why-dont-we-use-simpuserregistry)

What did **not** change yet:

- No move to `/topic/group.{groupId}.summary`
- No frontend subscription model change
- Per-member **loop and DB username scan** still run on each flush (publish step is skipped for offline users only)
