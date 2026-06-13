# Group Summary Update Fan-Out Scaling

## Current Problem

`GroupSummaryUpdatePublisher` currently performs **per-member fan-out** for every saved group message:

- Load all usernames in the group
- For each username:
  - `messagingTemplate.convertAndSend("/topic/user.{username}.group-updates", update)`
  - `rabbitMQBrokerHandler.publishToRabbitMQ("/topic/user.{username}.group-updates", update)`

This means the work is **O(group_members)** per saved group message.

Example for a busy group:

- Group size: **1,000 members**
- Message rate: **10 messages/second**

Current cost becomes approximately:

- **10,000 RabbitMQ publishes/second** for sidebar updates alone
- **10,000 local SimpleBroker sends/second**
- **10 username-list scans/second** from the database

Important nuance:

- The current RabbitMQ topology optimization in `10_RABBITMQ_TOPOLOGY_OPTIMIZATION.md` solved the **exchange / queue explosion** problem.
- It did **not** solve the **application-layer fan-out explosion** for sidebar summary updates.
- Async execution helps the sender request complete faster, but it does **not** reduce the actual amount of work done.

Why this becomes expensive:

1. **Cross-instance publish volume grows with group size.**
2. **Sidebar updates are usually less latency-sensitive than the main chat message stream**, so sending one event per member per message is often unnecessary.
3. **Busy large groups** are exactly where this pattern becomes hottest, because both message rate and member count are high.

## Possible Solutions

### 1. Debounce summary updates per group

**How it works**

- Keep the current per-user destination contract: `/topic/user.{username}.group-updates`.
- Instead of publishing immediately for every saved group message, buffer by `groupId` for a short window (for example 200ms to 1000ms).
- During the window, keep only the **latest** `GroupSummaryUpdate`.
- When the timer fires, publish one round of per-member updates for the latest state.

**Pros**

- Smallest implementation change.
- No frontend contract changes.
- Greatly reduces bursts during active chats.
- Works well when many messages arrive in a short period and only the latest sidebar preview matters.

**Cons**

- Still does **O(group_members)** publishes per debounce flush.
- Under sustained traffic, load is reduced but not fundamentally changed.
- Sidebar preview becomes slightly delayed.

**Recommendation for our problem:** **Yes** as the safest first optimization.

### 2. Publish one summary event per group to RabbitMQ, then fan out locally per instance

**How it works**

- Add a **group-scoped summary route**, for example:
  - STOMP-like internal destination concept: `/topic/group.{groupId}.summary`
  - RabbitMQ exchange / routing key: `chat.groups` / `group.{groupId}.summary`
- Each backend instance tracks whether it currently has **any connected user who is a member of that group**.
- If yes, that instance binds its inbound queue to `group.{groupId}.summary`.
- When a new group message is saved, publish **one** RabbitMQ message for the group summary.
- Only instances that currently host online members of that group receive it.
- On each receiving instance, fan out locally to the affected users.

**Pros**

- RabbitMQ publish cost becomes approximately **O(instances_with_online_members)** instead of **O(group_members)**.
- Keeps the existing optimized topology style: fixed exchanges, one queue per instance, dynamic bindings.
- Good fit for large groups spread across many instances.

**Cons**

- Requires new in-memory membership tracking per instance.
- Instance-local fan-out still exists, though it is much cheaper than cross-instance fan-out.
- Need clear lifecycle rules for connect, disconnect, session expiration, and membership changes.
- More complex than simple debounce.

**Recommendation for our problem:** **Yes** as the best structural backend optimization.

### 3. Publish one group summary topic to clients directly

**How it works**

- Expose a shared topic such as `/topic/group.{groupId}.summary`.
- All clients who care about that group's sidebar updates subscribe to the group-level summary topic.
- Backend publishes once per group update.

**Pros**

- Simplest publish path: one logical message per group update.
- Very low backend fan-out cost.

**Cons**

- Clients would need to subscribe to summary topics for **all groups they belong to**, not just the active chat.
- That can create many WebSocket subscriptions per client.
- Security and authorization become more sensitive because users must only receive summaries for groups they belong to.
- May shift scale pressure from publish cost to client subscription count and binding count.

**Recommendation for our problem:** **No** as the default path; reconsider only if client group counts are known to stay small.

### 4. Batch summary updates per user

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

- Still trends toward **O(affected_users)** work.
- More payload and buffering logic.
- Requires careful deduplication and ordering rules.

**Recommendation for our problem:** **Maybe** as a complement to debounce, not as the primary long-term fix.

### 5. Hybrid push + pull: push only invalidation, let client refresh summaries

**How it works**

- On group message, push only a lightweight signal such as:
  - `{ groupId: 42 }`
  - or `{ changedGroupIds: [...] }`
- The frontend then fetches fresh sidebar data from HTTP for the changed groups or the whole sidebar.

**Pros**

- Push payload becomes tiny.
- Easier to coalesce updates aggressively.
- Removes strict need for exact latest-summary payload in the event stream.

**Cons**

- Adds extra HTTP load.
- Can cause repeated fetches during busy chats unless the frontend also debounces.
- Worse user experience if every burst causes visible refresh churn.

**Recommendation for our problem:** **Maybe** if combined with debounce and client-side coalescing.

### 6. Dedicated group-summary worker / queue

**How it works**

- Main chat send path writes a lightweight "group summary changed" event to an internal queue.
- A worker aggregates, deduplicates, and emits summary updates separately from the main message path.

**Pros**

- Isolates summary-update load from the primary chat-send path.
- Better observability and backpressure control.
- Easier to add batching, retry, and rate limiting.

**Cons**

- More moving parts and operational complexity.
- Still needs a downstream fan-out strategy; by itself it does not solve per-member publish volume.

**Recommendation for our problem:** **Yes later** if summary updates become their own subsystem.

### 7. CQRS / read-model approach for sidebar state

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

**Recommendation for our problem:** **No for now**, **Yes** for very large scale.

## Recommendation

**Short term (lowest risk):** Solution **1** (debounce per group), optionally with Solution **4** (batch per user) if bursts are still too expensive.

**Medium term (best structural fix):** Solution **2** (one summary publish per group to RabbitMQ, local per-instance fan-out to online members).

**Long term (very large scale):** Solution **6** (dedicated summary worker) or Solution **7** (CQRS / read model), depending on how far the product grows.

Avoid relying only on the current async per-member publish path for large groups; it removes request blocking but not the core scaling cost.

## Chosen Solution

**Not implemented yet.** This document is analysis only.

Proposed implementation path:

1. Phase 1: Add **debounce per `groupId`** in `GroupSummaryUpdatePublisher`.
2. Phase 2: Move to **per-group RabbitMQ summary publish + per-instance local fan-out**.
3. Phase 3 (optional): Introduce a **dedicated summary worker / queue** if update volume is still high.
4. Phase 4 (optional): Revisit **CQRS / read model** if sidebar semantics outgrow event-push delivery.

### API / contract impacts

- Solution 1 can keep the current frontend contract unchanged: `/topic/user.{username}.group-updates`.
- Solution 2 can also keep the current frontend contract unchanged if the backend still fans out locally to each user's personal destination after receiving the group-scoped RabbitMQ event.
- Solution 3 would require frontend subscription changes and should be treated as a contract change.
- Solution 5 may require new HTTP refresh behavior on the frontend.

### Migration / backward compatibility

- Solution 1 is fully backward-compatible.
- Solution 2 can be introduced internally without frontend changes if the group-scoped event remains backend-only.
- During migration, both the old per-user direct publisher and the new group-scoped path could run behind a feature flag if needed.

### Metrics to add before implementation

- Group member count distribution
- `publishToGroupMembers()` call rate
- RabbitMQ publish count for `chat.user-updates`
- Average / P95 usernames per group publish
- Debounce flush count and batch size (if Solution 1 is implemented)
- Per-instance online member count per group (if Solution 2 is implemented)

## Future Higher-Scale Path

| Scale                                               | Suggested approach                                         |
| --------------------------------------------------- | ---------------------------------------------------------- |
| Small groups, low traffic                           | Current approach may be acceptable                         |
| Large groups (100+ members), bursty chat            | Debounce per group                                         |
| Large groups (1000+ members), many instances        | Per-group RabbitMQ publish + per-instance local fan-out    |
| Very busy clusters with sustained high message rate | Dedicated summary worker with batching / rate limiting     |
| Very large product scale                            | CQRS read model or hybrid push-invalidation + pull refresh |
