# Dynamic RabbitMQ Listener Registry Concurrency

## Current Problem

`DynamicRabbitMQListener` tracked `queueName → SimpleMessageListenerContainer` in a plain `HashMap`, with start doing **containsKey → create → put** and stop doing **remove → stop**.

That is unsafe if `startListening` / `stopListening` can overlap (e.g. concurrent subscribe/unsubscribe or broker-handler callbacks):

| Race         | What can go wrong                                                                           |
| ------------ | ------------------------------------------------------------------------------------------- |
| Two starts   | Both pass `containsKey`, both `start()` a container → duplicate consumers on the same queue |
| Start + stop | Half-updated map / lost entry / stop on wrong lifecycle                                     |

Related code: `DynamicRabbitMQListener.activeListeners`.

## Possible Solutions

### 1. `ConcurrentHashMap` + atomic register (chosen)

- How it works: `computeIfAbsent` creates/starts at most one container per queue; `remove` atomically takes ownership before `stop()`.
- Pros: Minimal change; matches “one listener per queue” invariant; no coarse `synchronized` on the whole class.
- Cons: Mapping function holds the key’s bin lock while `container.start()` runs — keep start fast.
- Recommendation: **Yes**.

### 2. Coarse `synchronized` on start/stop

- Pros: Simple to reason about.
- Cons: Serializes all queues through one lock.
- Recommendation: **No** unless registration becomes much more complex.

## Implementation details

- `activeListeners` is a `ConcurrentHashMap`.
- `startListening` uses `computeIfAbsent` (logs “already exists” when the mapping function did not run).
- `stopListening` still `remove` then `stop` — unchanged lifecycle, safer map ops.

## Lesson (look back here)

Shared mutable maps across threads need concurrent collections **and** atomic check-then-act (`computeIfAbsent` / `putIfAbsent`), not `HashMap` + `containsKey` + `put`. Same family of bugs as “shared mutable in-memory maps” in `.cursor/rules/avoid-race-conditions.instructions.mdc`.
