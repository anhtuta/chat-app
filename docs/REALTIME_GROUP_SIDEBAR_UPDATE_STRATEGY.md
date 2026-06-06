# Real-Time Group Sidebar Update Strategy

## Current Problem

When a user sends a message to a group, every group member can see the new message inside that group chat (because they subscribe to `/topic/group.<id>`). However, users who are **not** currently viewing that group cannot see the group's latest message update in the sidebar. The sidebar only reflects the state at page load time and does not refresh unless the user navigates away or refreshes the page.

This means the sidebar group list becomes stale as soon as any message is sent to a group the user is a member of but not currently viewing.

## Possible Solutions

### 1. Subscribe to all groups at once (FE side only)

How it works:

- On load, the frontend subscribes to the WebSocket topic of **every** group the user is a member of.
- Any incoming message on any of those topics updates the sidebar group preview.

Pros:

- Zero backend change.

Cons:

- Each subscription creates a RabbitMQ queue per instance per topic.
- Does not scale well when a user is in many groups.
- Sends full message payloads for groups the user is not viewing.

Recommendation for our problem:

- Not recommended when users can be in many groups.

### 2. User-scoped topic for group summary updates (chosen)

How it works:

- After saving a group message, the backend pushes a lightweight `GroupSummaryUpdate` event to a user-scoped topic per recipient (`/topic/user.<username>.group-updates`).
- The backend sends locally and also publishes to RabbitMQ so other app instances deliver to their local subscribers.
- Each user holds exactly **one** extra subscription regardless of how many groups they are in.
- The frontend receives the event and updates only the matching group row in the sidebar.

Pros:

- One personal subscription per user, regardless of group count.
- Works with the project's current RabbitMQ topic fanout strategy for cross-instance delivery.
- Only the summary fields are pushed (not full message payload).

Cons:

- Small backend change: fan out a summary event to each group member after save.
- Requires knowing all group members at send time (`GroupParticipantRepository.findByGroup`).

Recommendation for our problem:

- Recommended and implemented.

### 3. Polling

How it works:

- Frontend calls `GET /api/groups` on a timer (e.g. every few seconds).

Pros:

- Trivial to implement.

Cons:

- Not real-time (lag equals poll interval).
- Wasteful server load, especially with many connected users.

Recommendation for our problem:

- Not recommended as primary solution; acceptable as a degraded-mode fallback only.

### 4. Client-side local update only (optimistic)

How it works:

- The sender's frontend updates its own sidebar locally after sending.
- Other members still do not see the update in real time.

Pros:

- Zero round-trip cost for the sender.

Cons:

- Does not solve the problem for other group members.

Recommendation for our problem:

- Not applicable alone; only useful as a complement to options above.

## Chosen Solution

We use **user-specific personal queue for group summary updates**.

Implementation summary:

### Backend

- New DTO `GroupSummaryUpdate` contains: `groupId`, `latestMessage`, `latestMessageSender`, `latestMessageAt`.
- After saving a non-system group message, `WebSocketController.pushGroupSummaryUpdate(...)` builds `/topic/user.<username>.group-updates` for each participant, sends locally via `SimpMessagingTemplate.convertAndSend(...)`, and publishes to RabbitMQ via `CustomRabbitMQBrokerHandler.publishToRabbitMQ(...)`.

### Frontend

- `WebSocketProvider` exposes a new `subscribePersonal(topic, callback)` function that manages persistent subscriptions independent of the chat-switching `subscribe` function.
- On connect, `ChatPage` subscribes to `/topic/user.<username>.group-updates`.
- On receiving an update, `ChatPage` patches the matching group in the `groups` state array and then re-sorts the list by latest activity (`latestMessageAt` fallback `createdAt`) so the most recently active group moves to the top.

This gives every user real-time sidebar updates with a single extra subscription per user.

## Future Higher-Scale Path

If fan-out to many members per message becomes a bottleneck (e.g. very large groups):

- Move the fan-out to an async consumer (same event-driven pattern as the group-latest CAS upgrade path).
- Batch or debounce summary pushes for very frequent message bursts.
- Consider server-sent events (SSE) as an alternative transport for one-directional summary pushes if WebSocket overhead becomes significant.
